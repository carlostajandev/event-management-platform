project_name = "emp"
environment  = "dev"
aws_region   = "us-east-1"
vpc_cidr     = "10.0.0.0/16"
ecr_registry = "123456789.dkr.ecr.us-east-1.amazonaws.com"

service_image_tags = {
  event_service       = "latest"
  reservation_service = "latest"
  order_service       = "latest"
  consumer_service    = "latest"
}
