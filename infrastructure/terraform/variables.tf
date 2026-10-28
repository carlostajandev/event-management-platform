variable "project" {
  type    = string
  default = "ticketing"
}

variable "environment" {
  description = "dev or prod. Ideally each lives in its own AWS account; names are prefixed either way."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be dev or prod."
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  type = string
}

variable "image_tag" {
  description = "Image deployed when Terraform creates the services. Later deploys come from CI."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for HTTPS on the ALB. Empty serves HTTP."
  type        = string
  default     = ""
}

variable "waf_requests_per_5_minutes_per_ip" {
  type = number
}

variable "api" {
  type = object({
    cpu                    = number
    memory                 = number
    min_tasks              = number
    max_tasks              = number
    requests_per_target    = number
    max_in_flight_requests = number
    rate_limit_user_rps    = number
  })
}

variable "worker" {
  type = object({
    cpu               = number
    memory            = number
    min_tasks         = number
    max_tasks         = number
    backlog_scale_out = number
  })
}

variable "log_retention_days" {
  type = number
}

variable "container_insights" {
  type = bool
}

variable "protect_data" {
  description = "PITR, deletion protection on tables and the ALB."
  type        = bool
}

variable "alarm_email" {
  type    = string
  default = ""
}

variable "github_repository" {
  type = string
}

variable "create_oidc_provider" {
  type = bool
}
