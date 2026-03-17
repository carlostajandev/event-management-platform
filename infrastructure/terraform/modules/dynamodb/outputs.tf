output "table_names" {
  value = {
    events       = aws_dynamodb_table.events.name
    reservations = aws_dynamodb_table.reservations.name
    orders       = aws_dynamodb_table.orders.name
    outbox       = aws_dynamodb_table.outbox.name
    idempotency  = aws_dynamodb_table.idempotency.name
    audit        = aws_dynamodb_table.audit.name
  }
}

output "table_arns" {
  value = [
    aws_dynamodb_table.events.arn,
    aws_dynamodb_table.reservations.arn,
    aws_dynamodb_table.orders.arn,
    aws_dynamodb_table.outbox.arn,
    aws_dynamodb_table.idempotency.arn,
    aws_dynamodb_table.audit.arn,
  ]
}
