output "alb_dns_name" {
  description = "DNS name of the Application Load Balancer — point your Route53 record here"
  value       = module.ecs.alb_dns_name
}

output "dynamodb_tables" {
  description = "DynamoDB table names"
  value = {
    events      = module.dynamodb.events_table_name
    tickets     = module.dynamodb.tickets_table_name
    orders      = module.dynamodb.orders_table_name
    idempotency = module.dynamodb.idempotency_table_name
    audit       = module.dynamodb.audit_table_name
    shedlock    = module.dynamodb.shedlock_table_name
  }
}

output "sqs_queue_url" {
  description = "SQS purchase-orders queue URL"
  value       = module.sqs.queue_url
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

output "log_group_name" {
  description = "CloudWatch log group for application logs"
  value       = module.ecs.log_group_name
}
