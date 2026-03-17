locals {
  prefix = "${var.project_name}-${var.environment}"

  services = {
    event-service = {
      port  = 8081
      image = "${var.ecr_registry}/event-service:${var.service_image_tags.event_service}"
    }
    reservation-service = {
      port  = 8082
      image = "${var.ecr_registry}/reservation-service:${var.service_image_tags.reservation_service}"
    }
    order-service = {
      port  = 8083
      image = "${var.ecr_registry}/order-service:${var.service_image_tags.order_service}"
    }
    consumer-service = {
      port  = 8084
      image = "${var.ecr_registry}/consumer-service:${var.service_image_tags.consumer_service}"
    }
  }
}

# ── ECS Cluster ──────────────────────────────────────────────────
resource "aws_ecs_cluster" "main" {
  name = "${local.prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# ── ALB ──────────────────────────────────────────────────────────
resource "aws_lb" "main" {
  name               = "${local.prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  subnets            = var.public_subnet_ids

  security_groups = [aws_security_group.alb.id]
}

resource "aws_security_group" "alb" {
  name   = "${local.prefix}-alb-sg"
  vpc_id = var.vpc_id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ── ECS Tasks — one per microservice ─────────────────────────────
resource "aws_ecs_task_definition" "service" {
  for_each = local.services

  family                   = "${local.prefix}-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 512
  memory                   = 1024
  task_role_arn            = var.task_role_arn
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([{
    name      = each.key
    image     = each.value.image
    essential = true
    portMappings = [{
      containerPort = each.value.port
      protocol      = "tcp"
    }]
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
      { name = "AWS_REGION", value = var.aws_region },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/${local.prefix}-${each.key}"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
        "awslogs-create-group"  = "true"
      }
    }
    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:${each.value.port}/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])
}

resource "aws_security_group" "ecs_tasks" {
  name   = "${local.prefix}-ecs-tasks-sg"
  vpc_id = var.vpc_id

  ingress {
    from_port       = 0
    to_port         = 65535
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_ecs_service" "service" {
  for_each = local.services

  name            = "${local.prefix}-${each.key}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.service[each.key].arn
  desired_count   = 2
  launch_type     = "FARGATE"

  deployment_controller { type = "ECS" }

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }
}
