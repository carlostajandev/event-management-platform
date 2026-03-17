locals {
  prefix = "${var.project_name}-${var.environment}"
}

# ── Dead Letter Queue — receives messages after 3 failed delivery attempts ──
resource "aws_sqs_queue" "purchase_orders_dlq" {
  name                      = "${local.prefix}-purchase-orders-dlq"
  message_retention_seconds = 1209600 # 14 days
  kms_master_key_id         = "alias/aws/sqs"

  tags = { Name = "${local.prefix}-purchase-orders-dlq" }
}

# ── Main Purchase Orders Queue ───────────────────────────────────
# Visibility timeout (30s) MUST exceed max order processing time.
# Long polling (20s) reduces empty-receive API calls by ~95%.
# After 3 failures → DLQ.
resource "aws_sqs_queue" "purchase_orders" {
  name                       = "${local.prefix}-purchase-orders"
  visibility_timeout_seconds = 30
  receive_wait_time_seconds  = 20    # long polling
  message_retention_seconds  = 86400 # 24 hours
  kms_master_key_id          = "alias/aws/sqs"

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.purchase_orders_dlq.arn
    maxReceiveCount     = 3
  })

  tags = { Name = "${local.prefix}-purchase-orders" }
}
