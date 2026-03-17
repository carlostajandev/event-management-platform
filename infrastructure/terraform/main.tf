terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state — uncomment for team collaboration
  # backend "s3" {
  #   bucket = "nequi-emp-terraform-state"
  #   key    = "event-management-platform/terraform.tfstate"
  #   region = "us-east-1"
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# ── Modules ───────────────────────────────────────────────────────

module "networking" {
  source       = "./modules/networking"
  project_name = var.project_name
  environment  = var.environment
  vpc_cidr     = var.vpc_cidr
}

module "dynamodb" {
  source       = "./modules/dynamodb"
  project_name = var.project_name
  environment  = var.environment
}

module "sqs" {
  source       = "./modules/sqs"
  project_name = var.project_name
  environment  = var.environment
}

module "iam" {
  source              = "./modules/iam"
  project_name        = var.project_name
  environment         = var.environment
  dynamodb_table_arns = module.dynamodb.table_arns
  sqs_queue_arns      = module.sqs.queue_arns
}

module "ecs" {
  source             = "./modules/ecs"
  project_name       = var.project_name
  environment        = var.environment
  vpc_id             = module.networking.vpc_id
  private_subnet_ids = module.networking.private_subnet_ids
  public_subnet_ids  = module.networking.public_subnet_ids
  task_role_arn      = module.iam.ecs_task_role_arn
  execution_role_arn = module.iam.ecs_execution_role_arn
  aws_region         = var.aws_region
  service_image_tags = var.service_image_tags
  ecr_registry       = var.ecr_registry
}
