variable "app_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "app_image" {
  type = string
}

variable "app_port" {
  type = number
}

variable "task_cpu" {
  type = number
}

variable "task_memory" {
  type = number
}

variable "ecs_min_capacity" {
  type = number
}

variable "ecs_max_capacity" {
  type = number
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "alb_sg_id" {
  type = string
}

variable "ecs_sg_id" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "acm_certificate_arn" {
  type = string
}

variable "alb_access_logs_bucket" {
  type    = string
  default = ""
}

variable "sqs_queue_url" {
  type = string
}

variable "reservation_ttl_minutes" {
  type = number
}

variable "log_level" {
  type = string
}

variable "dynamodb_tables" {
  type = object({
    events      = string
    tickets     = string
    orders      = string
    idempotency = string
    audit       = string
  })
}
