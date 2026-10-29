output "api_url" {
  value = "${var.certificate_arn == "" ? "http" : "https"}://${module.edge.alb_dns_name}"
}

output "ecr_repository_url" {
  value = module.compute.ecr_repository_url
}

output "cluster_name" {
  value = module.compute.cluster_name
}

output "service_names" {
  value = module.compute.service_names
}

output "deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN variable of the matching GitHub environment."
  value       = module.ci.deploy_role_arn
}

output "alarm_topic_arn" {
  value = module.observability.alarm_topic_arn
}
