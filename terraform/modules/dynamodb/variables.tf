variable "app_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "billing_mode" {
  type    = string
  default = "PAY_PER_REQUEST"
}

variable "point_in_time_recovery" {
  type    = bool
  default = true
}
