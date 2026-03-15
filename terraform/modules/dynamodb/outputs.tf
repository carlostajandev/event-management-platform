output "events_table_name" { value = aws_dynamodb_table.events.name }
output "tickets_table_name" { value = aws_dynamodb_table.tickets.name }
output "orders_table_name" { value = aws_dynamodb_table.orders.name }
output "idempotency_table_name" { value = aws_dynamodb_table.idempotency.name }
output "audit_table_name" { value = aws_dynamodb_table.audit.name }
output "shedlock_table_name" { value = aws_dynamodb_table.shedlock.name }

output "tables_arns" {
  value = [
    aws_dynamodb_table.events.arn,
    aws_dynamodb_table.tickets.arn,
    aws_dynamodb_table.orders.arn,
    aws_dynamodb_table.idempotency.arn,
    aws_dynamodb_table.audit.arn,
    aws_dynamodb_table.shedlock.arn,
  ]
}
