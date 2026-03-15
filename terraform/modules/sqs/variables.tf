variable "app_name"                   { type = string }
variable "environment"                { type = string }
variable "visibility_timeout_seconds" { type = number; default = 30 }
variable "message_retention_seconds"  { type = number; default = 345600 }
variable "max_receive_count"          { type = number; default = 3 }
variable "alarm_sns_topic_arn"        { type = string; default = "" }
