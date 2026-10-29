# One task role per role, each limited to the calls its code makes (see the DynamoDB repositories).
# A transaction needs the per-item action on every table it touches; there is no "TransactWriteItems" action.

locals {
  arn        = var.table_arns
  all_tables = values(var.table_arns)
  index_arn  = { for key, table in var.table_arns : key => "${table}/index/*" }
}

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: used by ECS itself to pull the image and ship logs, never by the application.
resource "aws_iam_role" "execution" {
  name               = "${var.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "api" {
  name               = "${var.name}-api"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

# api: creates events and orders, reads them, confirms. Never touches the queue.
data "aws_iam_policy_document" "api" {
  statement {
    sid       = "Read"
    actions   = ["dynamodb:GetItem"]
    resources = [local.arn.events, local.arn.orders, local.arn.idempotency]
  }

  statement {
    sid       = "ListEvents"
    actions   = ["dynamodb:Query"]
    resources = [local.index_arn.events]
  }

  statement {
    sid       = "CreateInTransactions"
    actions   = ["dynamodb:PutItem"]
    resources = local.all_tables
  }

  statement {
    sid       = "TransitionInTransactions"
    actions   = ["dynamodb:UpdateItem"]
    resources = [local.arn.events, local.arn.orders]
  }

  statement {
    sid       = "HealthCheck"
    actions   = ["dynamodb:DescribeTable"]
    resources = local.all_tables
  }
}

resource "aws_iam_role_policy" "api" {
  name   = "dynamodb"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.api.json
}

resource "aws_iam_role" "worker" {
  name               = "${var.name}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

# worker: relays the outbox, processes orders, releases expired holds. Cannot create events or orders.
data "aws_iam_policy_document" "worker" {
  statement {
    sid       = "ReadOrders"
    actions   = ["dynamodb:GetItem"]
    resources = [local.arn.orders]
  }

  statement {
    sid       = "ScanSparseIndexes"
    actions   = ["dynamodb:Query"]
    resources = [local.index_arn.orders, local.index_arn.outbox]
  }

  statement {
    sid       = "TransitionInTransactions"
    actions   = ["dynamodb:UpdateItem"]
    resources = [local.arn.events, local.arn.orders, local.arn.outbox]
  }

  statement {
    sid       = "AuditTrail"
    actions   = ["dynamodb:PutItem"]
    resources = [local.arn.audit]
  }

  statement {
    sid       = "HealthCheck"
    actions   = ["dynamodb:DescribeTable"]
    resources = local.all_tables
  }

  statement {
    sid = "OrdersQueue"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
    ]
    resources = [var.orders_queue_arn]
  }
}

resource "aws_iam_role_policy" "worker" {
  name   = "dynamodb-sqs"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.worker.json
}
