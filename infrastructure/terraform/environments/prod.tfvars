project_name = "emp"
environment  = "prod"
aws_region   = "us-east-1"
vpc_cidr     = "10.1.0.0/16"
ecr_registry = "123456789.dkr.ecr.us-east-1.amazonaws.com"

service_image_tags = {
  event_service       = "stable"
  reservation_service = "stable"
  order_service       = "stable"
  consumer_service    = "stable"
}
