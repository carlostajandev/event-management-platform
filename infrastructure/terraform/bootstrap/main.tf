# Creates the bucket that holds one environment's Terraform state. Applied once per account, with local state:
#   terraform -chdir=bootstrap init && terraform -chdir=bootstrap apply -var environment=dev
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "environment" {
  type = string
}

variable "region" {
  type    = string
  default = "us-east-1"
}

provider "aws" {
  region = var.region
}

resource "aws_s3_bucket" "state" {
  #checkov:skip=CKV_AWS_144:State is rebuildable from code and versioned; cross-region replication is not worth it here.
  #checkov:skip=CKV_AWS_145:SSE-S3 encrypts the state; access is limited by IAM, not by a key policy.
  #checkov:skip=CKV_AWS_18:Access logging needs a second bucket; CloudTrail data events cover audits if required.
  #checkov:skip=CKV2_AWS_62:No consumer for bucket notifications.
  bucket = "ticketing-terraform-state-${var.environment}"

  lifecycle {
    prevent_destroy = true
  }
}

# Every state version is kept, so a bad apply can be inspected and rolled back.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Old state versions are kept 90 days, then removed.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "bucket" {
  value = aws_s3_bucket.state.bucket
}
