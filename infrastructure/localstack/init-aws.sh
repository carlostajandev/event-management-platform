#!/bin/bash
# ════════════════════════════════════════════════════════════════
# LocalStack initialization — runs on LocalStack startup
# Creates DynamoDB tables + SQS queues needed by all 4 services
# ════════════════════════════════════════════════════════════════

set -e
AWS="aws --endpoint-url=http://localhost:4566 --region us-east-1"

echo "==> Creating DynamoDB tables..."

# ── emp-events ──────────────────────────────────────────────────
# PK: EVENT#id, SK: METADATA
# GSI1: GSI1PK (STATUS#<status>) for listing active events
$AWS dynamodb create-table \
  --table-name emp-events \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
    AttributeName=GSI1PK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "GSI1",
      "KeySchema": [{"AttributeName":"GSI1PK","KeyType":"HASH"}],
      "Projection": {"ProjectionType":"ALL"}
    }
  ]' \
  --no-cli-pager 2>/dev/null && echo "  emp-events created" || echo "  emp-events already exists"

# ── emp-reservations ────────────────────────────────────────────
# PK: RESERVATION#id, SK: USER#userId
# GSI1: GSI1PK (STATUS#<status>), GSI1SK (expiresAt) — for expiry sweep (O(results) not O(table))
# TTL: "ttl" attribute (epoch seconds, 10 minutes)
$AWS dynamodb create-table \
  --table-name emp-reservations \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
    AttributeName=GSI1PK,AttributeType=S \
    AttributeName=GSI1SK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "GSI1",
      "KeySchema": [
        {"AttributeName":"GSI1PK","KeyType":"HASH"},
        {"AttributeName":"GSI1SK","KeyType":"RANGE"}
      ],
      "Projection": {"ProjectionType":"ALL"}
    }
  ]' \
  --no-cli-pager 2>/dev/null && echo "  emp-reservations created" || echo "  emp-reservations already exists"

$AWS dynamodb update-time-to-live \
  --table-name emp-reservations \
  --time-to-live-specification Enabled=true,AttributeName=ttl \
  --no-cli-pager 2>/dev/null || true

# ── emp-orders ──────────────────────────────────────────────────
# PK: ORDER#id, SK: RESERVATION#reservationId
# GSI1: GSI1PK (USER#userId) for user order history
$AWS dynamodb create-table \
  --table-name emp-orders \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
    AttributeName=GSI1PK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "GSI1",
      "KeySchema": [{"AttributeName":"GSI1PK","KeyType":"HASH"}],
      "Projection": {"ProjectionType":"ALL"}
    }
  ]' \
  --no-cli-pager 2>/dev/null && echo "  emp-orders created" || echo "  emp-orders already exists"

# ── emp-outbox ──────────────────────────────────────────────────
# PK: OUTBOX#id, SK: CREATED_AT#timestamp
# GSI1: GSI1PK (PUBLISHED#false) — for OutboxPoller to query unprocessed
# TTL: 24 hours
$AWS dynamodb create-table \
  --table-name emp-outbox \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
    AttributeName=GSI1PK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "GSI1",
      "KeySchema": [{"AttributeName":"GSI1PK","KeyType":"HASH"}],
      "Projection": {"ProjectionType":"ALL"}
    }
  ]' \
  --no-cli-pager 2>/dev/null && echo "  emp-outbox created" || echo "  emp-outbox already exists"

$AWS dynamodb update-time-to-live \
  --table-name emp-outbox \
  --time-to-live-specification Enabled=true,AttributeName=ttl \
  --no-cli-pager 2>/dev/null || true

# ── emp-idempotency-keys ─────────────────────────────────────────
# PK: KEY#uuid, SK: IDEMPOTENCY
# TTL: 24 hours
$AWS dynamodb create-table \
  --table-name emp-idempotency-keys \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --no-cli-pager 2>/dev/null && echo "  emp-idempotency-keys created" || echo "  emp-idempotency-keys already exists"

$AWS dynamodb update-time-to-live \
  --table-name emp-idempotency-keys \
  --time-to-live-specification Enabled=true,AttributeName=ttl \
  --no-cli-pager 2>/dev/null || true

# ── emp-audit ───────────────────────────────────────────────────
# PK: AUDIT#entityId, SK: TIMESTAMP#iso
# TTL: 90 days
$AWS dynamodb create-table \
  --table-name emp-audit \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=PK,AttributeType=S \
    AttributeName=SK,AttributeType=S \
  --key-schema \
    AttributeName=PK,KeyType=HASH \
    AttributeName=SK,KeyType=RANGE \
  --no-cli-pager 2>/dev/null && echo "  emp-audit created" || echo "  emp-audit already exists"

$AWS dynamodb update-time-to-live \
  --table-name emp-audit \
  --time-to-live-specification Enabled=true,AttributeName=ttl \
  --no-cli-pager 2>/dev/null || true

echo ""
echo "==> Creating SQS queues..."

# ── DLQ first (must exist before main queue's RedrivePolicy) ────
$AWS sqs create-queue \
  --queue-name emp-purchase-orders-dlq \
  --no-cli-pager 2>/dev/null && echo "  emp-purchase-orders-dlq created" || echo "  emp-purchase-orders-dlq already exists"

DLQ_ARN=$($AWS sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/emp-purchase-orders-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text 2>/dev/null)

# ── Main purchase-orders queue with DLQ redrive ─────────────────
$AWS sqs create-queue \
  --queue-name emp-purchase-orders \
  --attributes "{
    \"VisibilityTimeout\": \"30\",
    \"ReceiveMessageWaitTimeSeconds\": \"20\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
  }" \
  --no-cli-pager 2>/dev/null && echo "  emp-purchase-orders created (DLQ after 3 failures)" || echo "  emp-purchase-orders already exists"

echo ""
echo "==> LocalStack initialization complete!"
echo "    DynamoDB tables: emp-events, emp-reservations, emp-orders, emp-outbox, emp-idempotency-keys, emp-audit"
echo "    SQS queues: emp-purchase-orders (DLQ: emp-purchase-orders-dlq)"
echo ""
