output "queue_urls" {
  value = {
    purchase_orders     = aws_sqs_queue.purchase_orders.url
    purchase_orders_dlq = aws_sqs_queue.purchase_orders_dlq.url
  }
}

output "queue_arns" {
  value = [
    aws_sqs_queue.purchase_orders.arn,
    aws_sqs_queue.purchase_orders_dlq.arn,
  ]
}
