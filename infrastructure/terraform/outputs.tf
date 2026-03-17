output "vpc_id" {
  description = "VPC ID"
  value       = module.networking.vpc_id
}

output "dynamodb_table_names" {
  description = "All DynamoDB table names"
  value       = module.dynamodb.table_names
}

output "sqs_queue_urls" {
  description = "SQS queue URLs"
  value       = module.sqs.queue_urls
}

output "alb_dns_name" {
  description = "Application Load Balancer DNS name"
  value       = module.ecs.alb_dns_name
}

output "ecs_cluster_name" {
  description = "ECS Cluster name"
  value       = module.ecs.cluster_name
}
