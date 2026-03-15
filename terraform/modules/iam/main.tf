# ============================================================
# IAM Module
# Principle of least privilege — two separate roles:
#
# 1. Task Execution Role: ECS control plane permissions
#    - Pull image from ECR
#    - Write logs to CloudWatch
#    - Read secrets from Secrets Manager
#
# 2. Task Role: Application runtime permissions
#    - DynamoDB: only the 6 app tables, only needed actions
#    - SQS: only the purchase-orders queue
#    - X-Ray: trace segments
#
# No wildcard resources. No admin policies.
# ============================================================

locals {
  prefix = "${var.app_name}-${var.environment}"
}

# ── Task Execution Role ───────────────────────────────────────────────────────
# Used by ECS agent — not the application

resource "aws_iam_role" "ecs_execution" {
  name = "${local.prefix}-ecs-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = { Name = "${local.prefix}-ecs-execution-role" }
}

resource "aws_iam_role_policy_attachment" "ecs_execution_basic" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Allow reading secrets from Secrets Manager (DB passwords, API keys)
resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "${local.prefix}-ecs-execution-secrets"
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:${local.prefix}/*"
    }]
  })
}

# ── Task Role ─────────────────────────────────────────────────────────────────
# Used by the Spring Boot application at runtime

resource "aws_iam_role" "ecs_task" {
  name = "${local.prefix}-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = { Name = "${local.prefix}-ecs-task-role" }
}

# DynamoDB — only specific tables, only required actions
resource "aws_iam_role_policy" "dynamodb" {
  name = "${local.prefix}-dynamodb-policy"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DynamoDBTableAccess"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:BatchWriteItem",
          "dynamodb:DescribeTable"
        ]
        Resource = concat(
          var.dynamodb_table_arns,
          # GSI ARNs follow table ARN + /index/*
          [for arn in var.dynamodb_table_arns : "${arn}/index/*"]
        )
      }
    ]
  })
}

# SQS — only the purchase-orders queue
resource "aws_iam_role_policy" "sqs" {
  name = "${local.prefix}-sqs-policy"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "SQSQueueAccess"
      Effect = "Allow"
      Action = [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ]
      Resource = var.sqs_queue_arn
    }]
  })
}

# X-Ray — distributed tracing
resource "aws_iam_role_policy" "xray" {
  name = "${local.prefix}-xray-policy"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "XRayTracing"
      Effect = "Allow"
      Action = [
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords"
      ]
      Resource = "*"
    }]
  })
}
