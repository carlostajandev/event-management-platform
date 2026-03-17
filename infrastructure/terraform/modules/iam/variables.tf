variable "project_name"        { type = string }
variable "environment"         { type = string }
variable "dynamodb_table_arns" { type = list(string) }
variable "sqs_queue_arns"      { type = list(string) }
