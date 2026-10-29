output "table_names" {
  description = "Table names by logical name (events, orders, idempotency, audit, outbox)."
  value       = { for key, table in aws_dynamodb_table.this : key => table.name }
}

output "table_arns" {
  value = { for key, table in aws_dynamodb_table.this : key => table.arn }
}

output "orders_queue_name" {
  value = aws_sqs_queue.orders.name
}

output "orders_queue_arn" {
  value = aws_sqs_queue.orders.arn
}

output "orders_dlq_name" {
  value = aws_sqs_queue.orders_dlq.name
}
