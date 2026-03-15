# ============================================================
# SQS Module
# Creates: purchase-orders queue + DLQ
#
# Decisions:
# - DLQ with max_receive_count=3: after 3 failed attempts,
#   message moves to DLQ for investigation
# - Visibility timeout 30s: matches app processing expectation
# - Long polling (receive_wait_time_seconds=20): reduces
#   empty receives by 95%, cuts SQS costs significantly
# - KMS encryption at rest for financial message payloads
# ============================================================

locals {
  prefix = "${var.app_name}-${var.environment}"
}

# ── Dead Letter Queue ─────────────────────────────────────────────────────────

resource "aws_sqs_queue" "purchase_orders_dlq" {
  name                      = "${local.prefix}-purchase-orders-dlq"
  message_retention_seconds = 1209600 # 14 days — more time to investigate

  kms_master_key_id                 = "alias/aws/sqs"
  kms_data_key_reuse_period_seconds = 300

  tags = { Name = "${local.prefix}-purchase-orders-dlq", Type = "dlq" }
}

# ── Main Queue ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "purchase_orders" {
  name                       = "${local.prefix}-purchase-orders"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds
  receive_wait_time_seconds  = 20 # Long polling — reduces costs

  kms_master_key_id                 = "alias/aws/sqs"
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.purchase_orders_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  tags = { Name = "${local.prefix}-purchase-orders", Type = "main" }
}

# ── CloudWatch Alarm — DLQ messages ──────────────────────────────────────────
# Alert immediately when any message lands in DLQ — indicates processing failure

resource "aws_cloudwatch_metric_alarm" "dlq_messages" {
  alarm_name          = "${local.prefix}-dlq-messages"
  alarm_description   = "Messages in DLQ — order processing failures"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = aws_sqs_queue.purchase_orders_dlq.name
  }

  alarm_actions = var.alarm_sns_topic_arn != "" ? [var.alarm_sns_topic_arn] : []

  tags = { Name = "${local.prefix}-dlq-alarm" }
}
