# ============================================================
# Development environment configuration
# Apply with: terraform apply -var-file=environments/dev.tfvars
# ============================================================

environment = "dev"
aws_region  = "us-east-1"

vpc_cidr           = "10.1.0.0/16"
availability_zones = ["us-east-1a", "us-east-1b"]

app_image        = "ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/emp-dev:latest"
app_port         = 8080
ecs_task_cpu     = 256   # Smaller for cost savings in dev
ecs_task_memory  = 512
ecs_min_capacity = 1     # Single task in dev — no HA needed
ecs_max_capacity = 3

dynamodb_billing_mode           = "PAY_PER_REQUEST"
dynamodb_point_in_time_recovery = false  # Not needed in dev

sqs_visibility_timeout_seconds = 30
sqs_message_retention_seconds  = 86400  # 1 day in dev
sqs_max_receive_count          = 3

reservation_ttl_minutes = 60  # Longer TTL for dev testing
log_level               = "DEBUG"

cors_allowed_origins    = ["http://localhost:3000", "http://localhost:4200"]
acm_certificate_arn     = ""  # No HTTPS in dev
