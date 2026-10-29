# Runs without AWS: `terraform test` from modules/compute. Guards the properties the security review relies on.
mock_provider "aws" {
  # Policy documents are asserted on their statements; the rendered JSON only has to be valid.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{}" }
  }
}

variables {
  name                    = "ticketing-test"
  region                  = "us-east-1"
  vpc_id                  = "vpc-123"
  private_subnet_ids      = ["subnet-a", "subnet-b"]
  alb_security_group_id   = "sg-alb"
  target_group_arn        = "arn:aws:elasticloadbalancing:us-east-1:111111111111:targetgroup/api/1"
  listener_arn            = "arn:aws:elasticloadbalancing:us-east-1:111111111111:listener/app/x/1/2"
  alb_request_count_label = "app/x/1/targetgroup/api/1"
  table_names             = { events = "e", orders = "o", idempotency = "i", audit = "a", outbox = "x" }
  table_arns = {
    events      = "arn:aws:dynamodb:us-east-1:111111111111:table/e"
    orders      = "arn:aws:dynamodb:us-east-1:111111111111:table/o"
    idempotency = "arn:aws:dynamodb:us-east-1:111111111111:table/i"
    audit       = "arn:aws:dynamodb:us-east-1:111111111111:table/a"
    outbox      = "arn:aws:dynamodb:us-east-1:111111111111:table/x"
  }
  orders_queue_name  = "orders"
  orders_queue_arn   = "arn:aws:sqs:us-east-1:111111111111:orders"
  image_tag          = "test"
  api                = { cpu = 512, memory = 1024, min_tasks = 2, max_tasks = 4, requests_per_target = 1000, max_in_flight_requests = 64, rate_limit_user_rps = 5 }
  worker             = { cpu = 256, memory = 512, min_tasks = 1, max_tasks = 3, backlog_scale_out = 100 }
  log_retention_days = 7
  container_insights = false
}

run "api_role_cannot_use_the_queue" {
  command = plan

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.api.statement :
      alltrue([for action in statement.actions : !startswith(action, "sqs:")])
    ])
    error_message = "The api role must not have SQS permissions."
  }
}

run "worker_cannot_create_events_or_orders" {
  command = plan

  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.worker.statement :
      !contains(statement.actions, "dynamodb:PutItem") || statement.resources == toset([var.table_arns.audit])
    ])
    error_message = "The worker may only put audit items."
  }
}

run "no_wildcard_dynamodb_actions" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for document in [data.aws_iam_policy_document.api, data.aws_iam_policy_document.worker] : [
        for statement in document.statement : [for action in statement.actions : !endswith(action, "*")]
      ]
    ]))
    error_message = "Task roles list actions explicitly."
  }
}

run "tasks_are_private_and_rolled_back_on_failure" {
  command = plan

  assert {
    condition     = aws_ecs_service.api.network_configuration[0].assign_public_ip == false && aws_ecs_service.worker.network_configuration[0].assign_public_ip == false
    error_message = "Tasks must not get public IPs."
  }

  assert {
    condition     = aws_ecs_service.api.deployment_circuit_breaker[0].rollback && aws_ecs_service.worker.deployment_circuit_breaker[0].rollback
    error_message = "Failed deployments must roll back."
  }
}

run "api_sees_the_right_profile_and_no_per_ip_limit" {
  command = plan

  assert {
    condition     = local.roles.api.environment.SPRING_PROFILES_ACTIVE == "api" && local.roles.worker.environment.SPRING_PROFILES_ACTIVE == "worker"
    error_message = "Each service must run its own role."
  }

  assert {
    condition     = local.roles.api.environment.RATE_LIMIT_IP_RPS == "0"
    error_message = "Behind the ALB the per-address limit belongs to WAF."
  }
}

run "worker_baseline_is_on_demand" {
  command = plan

  assert {
    condition     = one([for strategy in aws_ecs_service.worker.capacity_provider_strategy : strategy.base if strategy.capacity_provider == "FARGATE"]) == var.worker.min_tasks
    error_message = "The minimum worker tasks must not run on Spot."
  }
}
