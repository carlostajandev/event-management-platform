#!/usr/bin/env bash
# Creates the local DynamoDB tables. Mirrors the tables defined in Terraform.
# Timestamps used as sort keys are epoch milliseconds (N) so they order numerically.
set -euo pipefail

DDB="aws dynamodb --endpoint-url ${DYNAMODB_ENDPOINT} --region ${AWS_REGION} --no-cli-pager"

until $DDB list-tables >/dev/null 2>&1; do
  echo "waiting for DynamoDB at ${DYNAMODB_ENDPOINT}..."
  sleep 1
done

create_table() {
  local name=$1; shift
  if $DDB describe-table --table-name "$name" >/dev/null 2>&1; then
    echo "table $name already exists"
  else
    $DDB create-table --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
    $DDB wait table-exists --table-name "$name"
    echo "table $name created"
  fi
}

# Idempotent like create_table: `docker compose up` can run again while DynamoDB Local keeps its tables.
enable_ttl() {
  local status
  status=$($DDB describe-time-to-live --table-name "$1" --query TimeToLiveDescription.TimeToLiveStatus --output text)
  if [ "$status" != "ENABLED" ]; then
    $DDB update-time-to-live --table-name "$1" \
      --time-to-live-specification Enabled=true,AttributeName=ttl >/dev/null
  fi
}

# Events: event metadata (SK=METADATA) and its inventory counters (SK=INVENTORY).
# GSI "events-by-date" lists events ordered by date.
create_table ticketing-events \
  --attribute-definitions AttributeName=PK,AttributeType=S AttributeName=SK,AttributeType=S \
                          AttributeName=listingKey,AttributeType=S AttributeName=eventDate,AttributeType=N \
  --key-schema AttributeName=PK,KeyType=HASH AttributeName=SK,KeyType=RANGE \
  --global-secondary-indexes '[{"IndexName":"events-by-date",
      "KeySchema":[{"AttributeName":"listingKey","KeyType":"HASH"},{"AttributeName":"eventDate","KeyType":"RANGE"}],
      "Projection":{"ProjectionType":"ALL"}}]'

# Orders. Sparse GSI "holds-by-expiry": only orders holding tickets (RESERVED, PENDING_CONFIRMATION)
# carry holdStatus, so the expiry job queries O(expired holds), never the whole table.
create_table ticketing-orders \
  --attribute-definitions AttributeName=PK,AttributeType=S \
                          AttributeName=holdStatus,AttributeType=S AttributeName=expiresAt,AttributeType=N \
  --key-schema AttributeName=PK,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"holds-by-expiry",
      "KeySchema":[{"AttributeName":"holdStatus","KeyType":"HASH"},{"AttributeName":"expiresAt","KeyType":"RANGE"}],
      "Projection":{"ProjectionType":"ALL"}}]'

# Idempotency keys, expired by TTL after 24 h.
create_table ticketing-idempotency \
  --attribute-definitions AttributeName=PK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH
enable_ttl ticketing-idempotency

# Audit trail: one item per state transition, keyed by order. No TTL: it backs accounting reports.
create_table ticketing-audit \
  --attribute-definitions AttributeName=PK,AttributeType=S AttributeName=SK,AttributeType=S \
  --key-schema AttributeName=PK,KeyType=HASH AttributeName=SK,KeyType=RANGE

# Outbox. Sparse GSI "pending-by-time": only unpublished messages carry pendingKey.
create_table ticketing-outbox \
  --attribute-definitions AttributeName=PK,AttributeType=S \
                          AttributeName=pendingKey,AttributeType=S AttributeName=createdAt,AttributeType=N \
  --key-schema AttributeName=PK,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"pending-by-time",
      "KeySchema":[{"AttributeName":"pendingKey","KeyType":"HASH"},{"AttributeName":"createdAt","KeyType":"RANGE"}],
      "Projection":{"ProjectionType":"ALL"}}]'
enable_ttl ticketing-outbox

echo "DynamoDB tables ready"
