locals {
  name = "${var.project}-${var.environment}"
}

module "network" {
  source = "./modules/network"

  name                    = local.name
  region                  = var.region
  cidr_block              = var.vpc_cidr
  flow_log_retention_days = var.log_retention_days
}

module "data" {
  source = "./modules/data"

  name         = local.name
  protect_data = var.protect_data
}

module "edge" {
  source = "./modules/edge"

  name                              = local.name
  vpc_id                            = module.network.vpc_id
  public_subnet_ids                 = module.network.public_subnet_ids
  certificate_arn                   = var.certificate_arn
  waf_requests_per_5_minutes_per_ip = var.waf_requests_per_5_minutes_per_ip
  protect_data                      = var.protect_data
}

module "compute" {
  source = "./modules/compute"

  name                    = local.name
  region                  = var.region
  vpc_id                  = module.network.vpc_id
  private_subnet_ids      = module.network.private_subnet_ids
  alb_security_group_id   = module.edge.alb_security_group_id
  target_group_arn        = module.edge.target_group_arn
  listener_arn            = module.edge.listener_arn
  alb_request_count_label = "${module.edge.alb_arn_suffix}/${module.edge.target_group_arn_suffix}"
  table_names             = module.data.table_names
  table_arns              = module.data.table_arns
  orders_queue_name       = module.data.orders_queue_name
  orders_queue_arn        = module.data.orders_queue_arn
  image_tag               = var.image_tag
  api                     = var.api
  worker                  = var.worker
  log_retention_days      = var.log_retention_days
  container_insights      = var.container_insights
}

module "observability" {
  source = "./modules/observability"

  name                    = local.name
  alarm_email             = var.alarm_email
  alb_arn_suffix          = module.edge.alb_arn_suffix
  target_group_arn_suffix = module.edge.target_group_arn_suffix
  table_names             = module.data.table_names
  orders_queue_name       = module.data.orders_queue_name
  orders_dlq_name         = module.data.orders_dlq_name
}

module "ci" {
  source = "./modules/ci"

  name                 = local.name
  github_repository    = var.github_repository
  github_environment   = var.environment
  create_oidc_provider = var.create_oidc_provider
  ecr_repository_arn   = module.compute.ecr_repository_arn
  cluster_name         = module.compute.cluster_name
  service_names        = module.compute.service_names
  pass_role_arns       = module.compute.role_arns
}
