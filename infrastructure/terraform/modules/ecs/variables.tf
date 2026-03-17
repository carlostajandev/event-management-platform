variable "project_name"        { type = string }
variable "environment"         { type = string }
variable "aws_region"          { type = string }
variable "vpc_id"              { type = string }
variable "private_subnet_ids"  { type = list(string) }
variable "public_subnet_ids"   { type = list(string) }
variable "task_role_arn"       { type = string }
variable "execution_role_arn"  { type = string }
variable "ecr_registry"        { type = string }
variable "service_image_tags" {
  type = object({
    event_service       = string
    reservation_service = string
    order_service       = string
    consumer_service    = string
  })
}
