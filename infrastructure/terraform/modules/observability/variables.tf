variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "alarm_email" {
  description = "Address subscribed to alarms. Empty creates the topic without subscribers."
  type        = string
  default     = ""
}

variable "alb_arn_suffix" {
  type = string
}

variable "target_group_arn_suffix" {
  type = string
}

variable "table_names" {
  type = map(string)
}

variable "orders_queue_name" {
  type = string
}

variable "orders_dlq_name" {
  type = string
}
