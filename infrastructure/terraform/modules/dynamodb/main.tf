locals {
  prefix = "${var.project_name}-${var.environment}"
}

# ── emp-events ──────────────────────────────────────────────────
# PK: EVENT#id, SK: METADATA
# GSI1: GSI1PK (STATUS#<status>) — for listing events by status
# Atomic counter: availableCount decremented via conditional UpdateItem
resource "aws_dynamodb_table" "events" {
  name         = "${local.prefix}-events"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK"     type = "S" }
  attribute { name = "SK"     type = "S" }
  attribute { name = "GSI1PK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }
>>>>>>> Stashed changes

  global_secondary_index {
    name            = "GSI1"
    hash_key        = "GSI1PK"
    projection_type = "ALL"
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-events" }
}

# ── emp-reservations ─────────────────────────────────────────────
# PK: RESERVATION#id, SK: USER#userId
# GSI1: GSI1PK (STATUS#ACTIVE) / GSI1SK (expiresAt ISO-8601 sortable)
#   → Used by reconciliation scheduler: O(results) not O(table)
# TTL: "ttl" (epoch seconds, 10 min) — DynamoDB auto-deletes expired items
resource "aws_dynamodb_table" "reservations" {
  name         = "${local.prefix}-reservations"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK"     type = "S" }
  attribute { name = "SK"     type = "S" }
  attribute { name = "GSI1PK" type = "S" }
  attribute { name = "GSI1SK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }

  attribute {
    name = "GSI1SK"
    type = "S"
  }
>>>>>>> Stashed changes

  global_secondary_index {
    name            = "GSI1"
    hash_key        = "GSI1PK"
    range_key       = "GSI1SK"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-reservations" }
}

# ── emp-orders ──────────────────────────────────────────────────
# PK: ORDER#id, SK: RESERVATION#reservationId
# GSI1: USER#userId — user order history
resource "aws_dynamodb_table" "orders" {
  name         = "${local.prefix}-orders"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK"     type = "S" }
  attribute { name = "SK"     type = "S" }
  attribute { name = "GSI1PK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }
>>>>>>> Stashed changes

  global_secondary_index {
    name            = "GSI1"
    hash_key        = "GSI1PK"
    projection_type = "ALL"
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-orders" }
}

# ── emp-outbox ──────────────────────────────────────────────────
# PK: OUTBOX#id, SK: CREATED_AT#timestamp
# GSI1: GSI1PK (PUBLISHED#false) — OutboxPoller queries only unpublished
# TTL: 24h — auto-cleanup of processed messages
resource "aws_dynamodb_table" "outbox" {
  name         = "${local.prefix}-outbox"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK"     type = "S" }
  attribute { name = "SK"     type = "S" }
  attribute { name = "GSI1PK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }
>>>>>>> Stashed changes

  global_secondary_index {
    name            = "GSI1"
    hash_key        = "GSI1PK"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-outbox" }
}

# ── emp-idempotency-keys ─────────────────────────────────────────
# PK: KEY#uuid, SK: IDEMPOTENCY
# TTL: 24h — cached responses expire with idempotency window
resource "aws_dynamodb_table" "idempotency" {
  name         = "${local.prefix}-idempotency-keys"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK" type = "S" }
  attribute { name = "SK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }
>>>>>>> Stashed changes

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-idempotency-keys" }
}

# ── emp-audit ───────────────────────────────────────────────────
# PK: AUDIT#entityId, SK: TIMESTAMP#iso
# TTL: 90 days — compliance retention window
resource "aws_dynamodb_table" "audit" {
  name         = "${local.prefix}-audit"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

<<<<<<< Updated upstream
  attribute { name = "PK" type = "S" }
  attribute { name = "SK" type = "S" }
=======
  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }
>>>>>>> Stashed changes

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }

  tags = { Name = "${local.prefix}-audit" }
}
