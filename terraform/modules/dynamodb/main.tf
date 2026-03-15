# ============================================================
# DynamoDB Module
# Creates all tables for the ticketing platform:
#   emp-events, emp-tickets, emp-orders,
#   emp-idempotency, emp-audit, emp-shedlock
#
# Decisions:
# - PAY_PER_REQUEST by default: no capacity planning needed,
#   handles unpredictable spikes (concert ticket sales)
# - PITR enabled: restore to any second in last 35 days
# - TTL on idempotency table: keys expire automatically — free
# - GSI on tickets: query by eventId+status without full scan
# ============================================================

locals {
  prefix = "${var.app_name}-${var.environment}"
}

# ── Events table ─────────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "events" {
  name         = "${local.prefix}-events"
  billing_mode = var.billing_mode
  hash_key     = "eventId"

  attribute {
    name = "eventId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled = true # AES-256 at rest
  }

  tags = { Name = "${local.prefix}-events", Table = "events" }
}

# ── Tickets table ─────────────────────────────────────────────────────────────
# GSI: eventId-status-index → query "all AVAILABLE tickets for event X"
# without full table scan — critical for reservation flow

resource "aws_dynamodb_table" "tickets" {
  name         = "${local.prefix}-tickets"
  billing_mode = var.billing_mode
  hash_key     = "ticketId"

  attribute {
    name = "ticketId"
    type = "S"
  }

  attribute {
    name = "eventId"
    type = "S"
  }

  attribute {
    name = "status"
    type = "S"
  }

  global_secondary_index {
    name            = "eventId-status-index"
    hash_key        = "eventId"
    range_key       = "status"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-tickets", Table = "tickets" }
}

# ── Orders table ──────────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "orders" {
  name         = "${local.prefix}-orders"
  billing_mode = var.billing_mode
  hash_key     = "orderId"

  attribute {
    name = "orderId"
    type = "S"
  }

  attribute {
    name = "userId"
    type = "S"
  }

  global_secondary_index {
    name            = "userId-index"
    hash_key        = "userId"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-orders", Table = "orders" }
}

# ── Idempotency table ─────────────────────────────────────────────────────────
# TTL enabled: DynamoDB deletes expired keys automatically — no cleanup job needed

resource "aws_dynamodb_table" "idempotency" {
  name         = "${local.prefix}-idempotency"
  billing_mode = var.billing_mode
  hash_key     = "idempotencyKey"

  attribute {
    name = "idempotencyKey"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-idempotency", Table = "idempotency" }
}

# ── Audit table ───────────────────────────────────────────────────────────────
# Composite key: entityId (PK) + timestamp (SK) → all changes for an entity in order

resource "aws_dynamodb_table" "audit" {
  name         = "${local.prefix}-audit"
  billing_mode = var.billing_mode
  hash_key     = "entityId"
  range_key    = "timestamp"

  attribute {
    name = "entityId"
    type = "S"
  }

  attribute {
    name = "timestamp"
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-audit", Table = "audit" }
}

# ── ShedLock table ────────────────────────────────────────────────────────────
# Used by ShedLock to coordinate distributed scheduler execution across ECS tasks

resource "aws_dynamodb_table" "shedlock" {
  name         = "${local.prefix}-shedlock"
  billing_mode = var.billing_mode
  hash_key     = "_id"

  attribute {
    name = "_id"
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-shedlock", Table = "shedlock" }
}
