output "queue_url" { value = aws_sqs_queue.purchase_orders.url }
output "queue_arn" { value = aws_sqs_queue.purchase_orders.arn }
output "dlq_url" { value = aws_sqs_queue.purchase_orders_dlq.url }
output "dlq_arn" { value = aws_sqs_queue.purchase_orders_dlq.arn }
