#!/bin/sh
EP="http://localhost:4566"
AWS="aws --endpoint-url $EP --region us-east-1"

DLQ_URL=$($AWS sqs create-queue \
  --queue-name emp-purchase-orders-dlq \
  --attributes MessageRetentionPeriod=1209600 \
  --query 'QueueUrl' --output text)

DLQ_ARN=$($AWS sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

$AWS sqs create-queue \
  --queue-name emp-purchase-orders \
  --attributes "VisibilityTimeout=30,MessageRetentionPeriod=86400,ReceiveMessageWaitTimeSeconds=20,RedrivePolicy={\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"3\"}"

echo "Colas SQS creadas:"
$AWS sqs list-queues
