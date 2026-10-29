variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "certificate_arn" {
  description = "ACM certificate for HTTPS. Empty serves plain HTTP (no domain in this assessment)."
  type        = string
  default     = ""
}

variable "waf_requests_per_5_minutes_per_ip" {
  description = "WAF rate-based rule: requests one IP may send in a 5-minute window before being blocked."
  type        = number
}

variable "protect_data" {
  description = "Deletion protection on the load balancer (prod)."
  type        = bool
}
