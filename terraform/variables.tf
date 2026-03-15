variable "aws_region" {
  description = "AWS region where all resources are deployed"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment: dev, staging, prod"
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, staging, prod."
  }
}

variable "app_name" {
  description = "Application name — used as prefix for all resource names"
  type        = string
  default     = "emp"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of AZs to use for high availability"
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "app_image" {
  description = "Docker image URI for the application (ECR)"
  type        = string
}

variable "app_port" {
  description = "Port the application listens on inside the container"
  type        = number
  default     = 8080
}

variable "ecs_task_cpu" {
  description = "CPU units for the ECS task (1024 = 1 vCPU)"
  type        = number
  default     = 512
}

variable "ecs_task_memory" {
  description = "Memory in MB for the ECS task"
  type        = number
  default     = 1024
}

variable "ecs_min_capacity" {
  description = "Minimum number of ECS tasks (HA requires >= 2)"
  type        = number
  default     = 2
}

variable "ecs_max_capacity" {
  description = "Maximum number of ECS tasks for auto-scaling"
  type        = number
  default     = 10
}

variable "dynamodb_billing_mode" {
  description = "DynamoDB billing mode: PAY_PER_REQUEST or PROVISIONED"
  type        = string
  default     = "PAY_PER_REQUEST"

  validation {
    condition     = contains(["PAY_PER_REQUEST", "PROVISIONED"], var.dynamodb_billing_mode)
    error_message = "Billing mode must be PAY_PER_REQUEST or PROVISIONED."
  }
}

variable "dynamodb_point_in_time_recovery" {
  description = "Enable PITR for DynamoDB tables (recommended for prod)"
  type        = bool
  default     = true
}

variable "sqs_message_retention_seconds" {
  description = "How long SQS retains undelivered messages (default 4 days)"
  type        = number
  default     = 345600
}

variable "sqs_visibility_timeout_seconds" {
  description = "Visibility timeout — must be >= app processing time"
  type        = number
  default     = 30
}

variable "sqs_max_receive_count" {
  description = "Max delivery attempts before sending to DLQ"
  type        = number
  default     = 3
}

variable "reservation_ttl_minutes" {
  description = "How long ticket reservations are held before expiring"
  type        = number
  default     = 10
}

variable "log_level" {
  description = "Application log level"
  type        = string
  default     = "INFO"
}

variable "cors_allowed_origins" {
  description = "Allowed CORS origins for the API"
  type        = list(string)
  default     = []
}

variable "acm_certificate_arn" {
  description = "ACM certificate ARN for HTTPS on ALB"
  type        = string
  default     = ""
}
