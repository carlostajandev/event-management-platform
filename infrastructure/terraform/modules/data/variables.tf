variable "name" {
  description = "Prefix for resource names (project-environment)."
  type        = string
}

variable "protect_data" {
  description = "Point-in-time recovery and deletion protection. On in prod, off in dev so it can be torn down."
  type        = bool
}

variable "order_visibility_timeout_seconds" {
  description = "How long a received message stays hidden; must exceed the time the worker needs to process one."
  type        = number
  default     = 30
}

variable "max_receive_count" {
  description = "Deliveries before a message goes to the dead-letter queue."
  type        = number
  default     = 3
}
