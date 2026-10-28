variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "github_repository" {
  description = "owner/repo allowed to deploy."
  type        = string
}

variable "github_environment" {
  description = "GitHub environment whose jobs may assume the deploy role (its protection rules gate the deploy)."
  type        = string
}

variable "create_oidc_provider" {
  description = "The GitHub OIDC provider exists once per account; only the first environment in an account creates it."
  type        = bool
}

variable "ecr_repository_arn" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "service_names" {
  type = map(string)
}

variable "pass_role_arns" {
  type = list(string)
}
