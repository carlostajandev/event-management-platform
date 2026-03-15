# ============================================================
# Event Management Platform — Root Terraform Configuration
# Wires all modules together
# ============================================================

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ── Networking ────────────────────────────────────────────────────────────────

module "networking" {
  source = "./modules/networking"

  app_name           = var.app_name
  environment        = var.environment
  aws_region         = var.aws_region
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  app_port           = var.app_port
}

# ── DynamoDB ──────────────────────────────────────────────────────────────────

module "dynamodb" {
  source = "./modules/dynamodb"

  app_name               = var.app_name
  environment            = var.environment
  billing_mode           = var.dynamodb_billing_mode
  point_in_time_recovery = var.dynamodb_point_in_time_recovery
}

# ── SQS ───────────────────────────────────────────────────────────────────────

module "sqs" {
  source = "./modules/sqs"

  app_name                   = var.app_name
  environment                = var.environment
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  message_retention_seconds  = var.sqs_message_retention_seconds
  max_receive_count          = var.sqs_max_receive_count
}

# ── IAM ───────────────────────────────────────────────────────────────────────

module "iam" {
  source = "./modules/iam"

  app_name            = var.app_name
  environment         = var.environment
  aws_region          = var.aws_region
  account_id          = data.aws_caller_identity.current.account_id
  dynamodb_table_arns = module.dynamodb.tables_arns
  sqs_queue_arn       = module.sqs.queue_arn
}

# ── ECS ───────────────────────────────────────────────────────────────────────

module "ecs" {
  source = "./modules/ecs"

  app_name         = var.app_name
  environment      = var.environment
  aws_region       = var.aws_region
  app_image        = var.app_image
  app_port         = var.app_port
  task_cpu         = var.ecs_task_cpu
  task_memory      = var.ecs_task_memory
  ecs_min_capacity = var.ecs_min_capacity
  ecs_max_capacity = var.ecs_max_capacity

  vpc_id             = module.networking.vpc_id
  public_subnet_ids  = module.networking.public_subnet_ids
  private_subnet_ids = module.networking.private_subnet_ids
  alb_sg_id          = module.networking.alb_sg_id
  ecs_sg_id          = module.networking.ecs_sg_id

  execution_role_arn = module.iam.execution_role_arn
  task_role_arn      = module.iam.task_role_arn

  acm_certificate_arn     = var.acm_certificate_arn
  sqs_queue_url           = module.sqs.queue_url
  reservation_ttl_minutes = var.reservation_ttl_minutes
  log_level               = var.log_level

  dynamodb_tables = {
    events      = module.dynamodb.events_table_name
    tickets     = module.dynamodb.tickets_table_name
    orders      = module.dynamodb.orders_table_name
    idempotency = module.dynamodb.idempotency_table_name
    audit       = module.dynamodb.audit_table_name
  }
}
