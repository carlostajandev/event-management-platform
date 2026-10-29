output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "ecr_repository_arn" {
  value = aws_ecr_repository.this.arn
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_names" {
  value = { api = aws_ecs_service.api.name, worker = aws_ecs_service.worker.name }
}

output "role_arns" {
  description = "Roles CI must be allowed to pass when it registers a task definition."
  value       = [aws_iam_role.execution.arn, aws_iam_role.api.arn, aws_iam_role.worker.arn]
}
