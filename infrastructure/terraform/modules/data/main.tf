# Same schema as infrastructure/local/dynamodb-init.sh, so the code runs unchanged against both.
# On-demand capacity: a flash sale is a spike that provisioned capacity would either throttle or overpay for.
# Encryption at rest uses the AWS owned key (DynamoDB default) and SQS managed keys: no key policy to manage
# and no KMS cost. A customer managed key is a variable away if regulation requires key ownership.

locals {
  tables = {
    events = {
      range_key  = "SK"
      attributes = { PK = "S", SK = "S", listingKey = "S", eventDate = "N" }
      indexes    = { "events-by-date" = { hash = "listingKey", range = "eventDate" } }
      ttl        = false
    }
    # Sparse index: only orders holding tickets carry holdStatus, so the expiry job reads only live holds.
    orders = {
      range_key  = null
      attributes = { PK = "S", holdStatus = "S", expiresAt = "N" }
      indexes    = { "holds-by-expiry" = { hash = "holdStatus", range = "expiresAt" } }
      ttl        = false
    }
    idempotency = {
      range_key  = null
      attributes = { PK = "S" }
      indexes    = {}
      ttl        = true # keys expire after 24 h
    }
    # No TTL: the audit trail backs accounting reports.
    audit = {
      range_key  = "SK"
      attributes = { PK = "S", SK = "S" }
      indexes    = {}
      ttl        = false
    }
    # Sparse index: only unpublished messages carry pendingKey.
    outbox = {
      range_key  = null
      attributes = { PK = "S", pendingKey = "S", createdAt = "N" }
      indexes    = { "pending-by-time" = { hash = "pendingKey", range = "createdAt" } }
      ttl        = true # published messages are removed after 24 h
    }
  }
}

resource "aws_dynamodb_table" "this" {
  #checkov:skip=CKV_AWS_119:Encrypted with the AWS owned key by design (see the header); a CMK adds cost and a key policy to run.
  for_each = local.tables

  name         = "${var.name}-${each.key}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = each.value.range_key

  deletion_protection_enabled = var.protect_data

  dynamic "attribute" {
    for_each = each.value.attributes
    content {
      name = attribute.key
      type = attribute.value
    }
  }

  dynamic "global_secondary_index" {
    for_each = each.value.indexes
    content {
      name            = global_secondary_index.key
      hash_key        = global_secondary_index.value.hash
      range_key       = global_secondary_index.value.range
      projection_type = "ALL"
    }
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [1] : []
    content {
      attribute_name = "ttl"
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = var.protect_data
  }

  server_side_encryption {
    enabled = false # false = AWS owned key, still encrypted at rest
  }
}

resource "aws_sqs_queue" "orders_dlq" {
  name                      = "${var.name}-orders-dlq"
  message_retention_seconds = 1209600 # 14 days to inspect and redrive
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "orders" {
  name                       = "${var.name}-orders"
  visibility_timeout_seconds = var.order_visibility_timeout_seconds
  receive_wait_time_seconds  = 20 # long polling: fewer empty receives
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.orders_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "orders_dlq" {
  queue_url = aws_sqs_queue.orders_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.orders.arn]
  })
}

# Only TLS connections to the queues.
data "aws_iam_policy_document" "tls_only" {
  for_each = {
    orders = aws_sqs_queue.orders.arn
    dlq    = aws_sqs_queue.orders_dlq.arn
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "tls_only" {
  for_each = {
    orders = aws_sqs_queue.orders.id
    dlq    = aws_sqs_queue.orders_dlq.id
  }

  queue_url = each.value
  policy    = data.aws_iam_policy_document.tls_only[each.key].json
}
