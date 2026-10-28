variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "target_group_arn" {
  type = string
}

variable "listener_arn" {
  description = "Only used to order creation: the api service needs the listener before it registers targets."
  type        = string
}

variable "alb_request_count_label" {
  description = "app/<alb>/<id>/targetgroup/<tg>/<id>, the resource label for request-count scaling."
  type        = string
}

variable "table_names" {
  type = map(string)
}

variable "table_arns" {
  type = map(string)
}

variable "orders_queue_name" {
  type = string
}

variable "orders_queue_arn" {
  type = string
}

variable "image_tag" {
  description = "Tag of the image in this environment's ECR repository. CI deploys newer tags without Terraform."
  type        = string
}

variable "api" {
  description = "api role sizing and scaling."
  type = object({
    cpu                    = number
    memory                 = number
    min_tasks              = number
    max_tasks              = number
    requests_per_target    = number # ALB requests per task per minute before scaling out
    max_in_flight_requests = number
    rate_limit_user_rps    = number
  })
}

variable "worker" {
  description = "worker role sizing and scaling."
  type = object({
    cpu               = number
    memory            = number
    min_tasks         = number
    max_tasks         = number
    backlog_scale_out = number # visible messages that add a task
  })
}

variable "log_retention_days" {
  type = number
}

variable "container_insights" {
  description = "Per-task CPU/memory metrics. Costs extra; worth it in prod."
  type        = bool
}
