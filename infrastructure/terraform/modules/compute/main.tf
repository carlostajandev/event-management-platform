# One image, two ECS services. Each service runs the same image with a different Spring profile, its own task
# role and its own scaling signal: the api scales on traffic, the worker on queue depth.

locals {
  container_port = 8080
  image          = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  common_environment = {
    AWS_REGION        = var.region
    TABLE_EVENTS      = var.table_names.events
    TABLE_ORDERS      = var.table_names.orders
    TABLE_IDEMPOTENCY = var.table_names.idempotency
    TABLE_AUDIT       = var.table_names.audit
    TABLE_OUTBOX      = var.table_names.outbox
    QUEUE_ORDERS      = var.orders_queue_name
  }

  roles = {
    api = {
      task_role = aws_iam_role.api.arn
      cpu       = var.api.cpu
      memory    = var.api.memory
      environment = merge(local.common_environment, {
        SPRING_PROFILES_ACTIVE = "api"
        MAX_IN_FLIGHT_REQUESTS = tostring(var.api.max_in_flight_requests)
        RATE_LIMIT_USER_RPS    = tostring(var.api.rate_limit_user_rps)
        RATE_LIMIT_IP_RPS      = "0" # WAF limits per IP; here the peer address is the ALB
      })
    }
    worker = {
      task_role   = aws_iam_role.worker.arn
      cpu         = var.worker.cpu
      memory      = var.worker.memory
      environment = merge(local.common_environment, { SPRING_PROFILES_ACTIVE = "worker" })
    }
  }
}

resource "aws_ecr_repository" "this" {
  #checkov:skip=CKV_AWS_136:ECR encrypts images with AES-256 by default; a KMS key is not needed for application images.
  name                 = var.name
  image_tag_mutability = "IMMUTABLE" # a tag always means the same image; rollbacks are exact

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_ecs_cluster" "this" {
  #checkov:skip=CKV_AWS_65:Container insights is a variable: on in prod, off in dev to save cost.
  name = var.name

  setting {
    name  = "containerInsights"
    value = var.container_insights ? "enabled" : "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_cloudwatch_log_group" "this" {
  #checkov:skip=CKV_AWS_158:CloudWatch Logs encrypts at rest by default; logs carry no secrets or personal data.
  for_each = local.roles

  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_security_group" "tasks" {
  #checkov:skip=CKV2_AWS_5:Attached to the ECS services below; the check does not resolve for_each references.
  for_each = local.roles

  name        = "${var.name}-${each.key}"
  description = "${each.key} tasks"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.tasks["api"].id
  description                  = "HTTP from the load balancer only"
  ip_protocol                  = "tcp"
  from_port                    = local.container_port
  to_port                      = local.container_port
  referenced_security_group_id = var.alb_security_group_id
}

# The worker accepts no inbound traffic at all.

# Outbound HTTPS only, and the route tables only lead to VPC endpoints.
resource "aws_vpc_security_group_egress_rule" "tasks_https" {
  for_each = local.roles

  security_group_id = aws_security_group.tasks[each.key].id
  description       = "HTTPS to AWS services through VPC endpoints"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_ecs_task_definition" "this" {
  for_each = local.roles

  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = each.value.task_role

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # Writable scratch space; the rest of the filesystem is read-only.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([{
    name                   = each.key
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = local.container_port, protocol = "tcp" }]
    environment            = [for key, value in each.value.environment : { name = key, value = value }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp" }]

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:${local.container_port}/actuator/health/readiness || exit 1"]
      interval    = 10
      timeout     = 3
      retries     = 3
      startPeriod = 30
    }

    # Spring shuts down gracefully on SIGTERM; give it time to finish requests and in-flight messages.
    stopTimeout = 30

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name            = "${var.name}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this["api"].arn
  desired_count   = var.api.min_tasks
  launch_type     = "FARGATE"

  health_check_grace_period_seconds  = 60
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # Rolls back a deployment whose tasks never become healthy.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks["api"].id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "api"
    container_port   = local.container_port
  }

  # CI registers new task definitions on deploy and autoscaling owns the count.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [var.listener_arn]
}

resource "aws_ecs_service" "worker" {
  name            = "${var.name}-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this["worker"].arn
  desired_count   = var.worker.min_tasks

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # The baseline runs on regular Fargate; tasks added for a backlog run on Spot. Losing a Spot task is safe:
  # unacknowledged messages become visible again and every transition is idempotent.
  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = var.worker.min_tasks
    weight            = 1
  }

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 3
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks["worker"].id]
    assign_public_ip = false
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}
