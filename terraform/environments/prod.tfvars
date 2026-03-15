# ============================================================
# Production environment configuration
# Apply with: terraform apply -var-file=environments/prod.tfvars
# ============================================================

environment = "prod"
aws_region  = "us-east-1"

# Networking
vpc_cidr           = "10.0.0.0/16"
availability_zones = ["us-east-1a", "us-east-1b", "us-east-1c"]

# ECS — start with 2 tasks minimum for HA, scale to 20 on spikes
app_image        = "ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/emp-prod:latest"
app_port         = 8080
ecs_task_cpu     = 1024 # 1 vCPU in production
ecs_task_memory  = 2048 # 2 GB in production
ecs_min_capacity = 2
ecs_max_capacity = 20

# DynamoDB — on-demand billing + PITR for financial data
dynamodb_billing_mode           = "PAY_PER_REQUEST"
dynamodb_point_in_time_recovery = true

# SQS
sqs_visibility_timeout_seconds = 30
sqs_message_retention_seconds  = 345600 # 4 days
sqs_max_receive_count          = 3

# Business rules
reservation_ttl_minutes = 10
log_level               = "INFO"

# CORS — only allow known frontends in production
cors_allowed_origins = [
  "https://ticketing.nequi.com",
  "https://app.nequi.com"
]

# Replace with your ACM certificate ARN
acm_certificate_arn = "arn:aws:acm:us-east-1:ACCOUNT_ID:certificate/CERT_ID"
