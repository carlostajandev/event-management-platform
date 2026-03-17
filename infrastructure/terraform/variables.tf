variable "project_name" {
  description = "Project name prefix for all AWS resources"
  type        = string
  default     = "emp"
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging, or prod"
  }
}

variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
  default     = "10.0.0.0/16"
}

variable "ecr_registry" {
  description = "ECR registry URL (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com)"
  type        = string
}

variable "service_image_tags" {
  description = "Docker image tags for each microservice"
  type = object({
    event_service       = string
    reservation_service = string
    order_service       = string
    consumer_service    = string
  })
  default = {
    event_service       = "latest"
    reservation_service = "latest"
    order_service       = "latest"
    consumer_service    = "latest"
  }
}
