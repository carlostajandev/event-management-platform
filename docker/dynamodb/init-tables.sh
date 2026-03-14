#!/bin/sh
EP="http://dynamodb-local:8000"
AWS="aws --endpoint-url $EP --region us-east-1"

echo "Esperando DynamoDB Local..."
sleep 3

$AWS dynamodb create-table \
  --table-name emp-events \
  --attribute-definitions AttributeName=eventId,AttributeType=S \
  --key-schema AttributeName=eventId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST 2>/dev/null || echo "emp-events ya existe"

$AWS dynamodb create-table \
  --table-name emp-tickets \
  --attribute-definitions \
    AttributeName=ticketId,AttributeType=S \
    AttributeName=eventId,AttributeType=S \
    AttributeName=status,AttributeType=S \
  --key-schema AttributeName=ticketId,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"eventId-status-index","KeySchema":[{"AttributeName":"eventId","KeyType":"HASH"},{"AttributeName":"status","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST 2>/dev/null || echo "emp-tickets ya existe"

$AWS dynamodb create-table \
  --table-name emp-orders \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"userId-index","KeySchema":[{"AttributeName":"userId","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST 2>/dev/null || echo "emp-orders ya existe"

$AWS dynamodb create-table \
  --table-name emp-idempotency \
  --attribute-definitions AttributeName=idempotencyKey,AttributeType=S \
  --key-schema AttributeName=idempotencyKey,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST 2>/dev/null || echo "emp-idempotency ya existe"

$AWS dynamodb update-time-to-live \
  --table-name emp-idempotency \
  --time-to-live-specification "Enabled=true,AttributeName=expiresAt" 2>/dev/null || true

$AWS dynamodb create-table \
  --table-name emp-audit \
  --attribute-definitions \
    AttributeName=entityId,AttributeType=S \
    AttributeName=timestamp,AttributeType=S \
  --key-schema \
    AttributeName=entityId,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST 2>/dev/null || echo "emp-audit ya existe"

echo "Tablas listas:"
$AWS dynamodb list-tables
