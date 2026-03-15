# ============================================================
# ECS Module
# Creates: ALB, Target Group, ECS Cluster, Task Definition,
#          ECS Service, Auto Scaling, CloudWatch Log Group
#
# Decisions:
# - Fargate (not EC2): no instance management, pay-per-task
# - Multi-AZ: tasks spread across 3 AZs for HA
# - Rolling deployment: min 50% healthy — zero downtime
# - Auto scaling on ALB request count: reactive to traffic
# - Container env vars from Secrets Manager via ECS secrets
# ============================================================

locals {
  prefix = "${var.app_name}-${var.environment}"
}

# ── CloudWatch Log Group ──────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.prefix}"
  retention_in_days = var.environment == "prod" ? 90 : 14

  tags = { Name = "${local.prefix}-logs" }
}

# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = "${local.prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled" # CloudWatch Container Insights for detailed metrics
  }

  tags = { Name = "${local.prefix}-cluster" }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = var.ecs_min_capacity # Always run this many on FARGATE
  }
}

# ── Task Definition ───────────────────────────────────────────────────────────

resource "aws_ecs_task_definition" "app" {
  family                   = "${local.prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([{
    name      = "${local.prefix}-app"
    image     = var.app_image
    essential = true

    portMappings = [{
      containerPort = var.app_port
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
      { name = "SERVER_PORT", value = tostring(var.app_port) },
      { name = "AWS_REGION", value = var.aws_region },
      { name = "DYNAMODB_TABLE_EVENTS", value = var.dynamodb_tables.events },
      { name = "DYNAMODB_TABLE_TICKETS", value = var.dynamodb_tables.tickets },
      { name = "DYNAMODB_TABLE_ORDERS", value = var.dynamodb_tables.orders },
      { name = "DYNAMODB_TABLE_IDEMPOTENCY", value = var.dynamodb_tables.idempotency },
      { name = "DYNAMODB_TABLE_AUDIT", value = var.dynamodb_tables.audit },
      { name = "SQS_QUEUE_ORDERS", value = var.sqs_queue_url },
      { name = "RESERVATION_TTL_MINUTES", value = tostring(var.reservation_ttl_minutes) },
      { name = "LOG_LEVEL_APP", value = var.log_level },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:${var.app_port}/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60 # Give Spring Boot time to start
    }

    # Soft memory limit — container can use up to task memory
    memoryReservation = var.task_memory / 2
  }])

  tags = { Name = "${local.prefix}-task-def" }
}

# ── Application Load Balancer ─────────────────────────────────────────────────

resource "aws_lb" "main" {
  name               = "${local.prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_sg_id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = var.environment == "prod"

  access_logs {
    bucket  = var.alb_access_logs_bucket
    prefix  = "${local.prefix}-alb"
    enabled = var.environment == "prod"
  }

  tags = { Name = "${local.prefix}-alb" }
}

resource "aws_lb_target_group" "app" {
  name        = "${local.prefix}-tg"
  port        = var.app_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Required for Fargate awsvpc mode

  health_check {
    enabled             = true
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  deregistration_delay = 30 # Reduce to allow fast rolling deploys

  tags = { Name = "${local.prefix}-tg" }
}

# HTTP → HTTPS redirect
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# ── ECS Service ───────────────────────────────────────────────────────────────

resource "aws_ecs_service" "app" {
  name            = "${local.prefix}-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.ecs_min_capacity
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 50  # Rolling deploy — half stay up
  deployment_maximum_percent         = 200 # Can run 2x during deploy

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.ecs_sg_id]
    assign_public_ip = false # Private subnet + VPC endpoints
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "${local.prefix}-app"
    container_port   = var.app_port
  }

  # Spread tasks across AZs for HA
  placement_constraints {
    type = "distinctInstance"
  }

  lifecycle {
    ignore_changes = [desired_count] # Auto-scaling manages this
  }

  depends_on = [aws_lb_listener.https]

  tags = { Name = "${local.prefix}-service" }
}

# ── Auto Scaling ──────────────────────────────────────────────────────────────

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = var.ecs_max_capacity
  min_capacity       = var.ecs_min_capacity
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "scale_on_requests" {
  name               = "${local.prefix}-scale-requests"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 1000 # Requests per target per minute
    scale_in_cooldown  = 300  # 5 min — don't scale in too fast
    scale_out_cooldown = 60   # 1 min — scale out quickly on spikes

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.main.arn_suffix}/${aws_lb_target_group.app.arn_suffix}"
    }
  }
}

# Also scale on CPU as secondary signal
resource "aws_appautoscaling_policy" "scale_on_cpu" {
  name               = "${local.prefix}-scale-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 70 # Scale out at 70% CPU
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
