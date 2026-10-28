variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "region" {
  type = string
}

variable "cidr_block" {
  type = string
}

variable "flow_log_retention_days" {
  type = number
}

variable "az_count" {
  description = "Availability zones to spread subnets over. Two keeps the ALB and services multi-AZ."
  type        = number
  default     = 2
}
