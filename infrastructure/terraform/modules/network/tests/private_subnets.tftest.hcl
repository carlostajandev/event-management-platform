mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{}" }
  }

  # ARNs are validated, so the mock returns well-formed ones.
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:111111111111:log-group:test" }
  }

  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/test" }
  }

  override_data {
    target = data.aws_availability_zones.available
    values = { names = ["us-east-1a", "us-east-1b", "us-east-1c"] }
  }
}

variables {
  name                    = "ticketing-test"
  region                  = "us-east-1"
  cidr_block              = "10.10.0.0/16"
  flow_log_retention_days = 7
}

# Routes are computed, so this run applies against the mock provider (nothing is created in AWS).
run "private_subnets_have_no_route_to_the_internet" {
  command = apply

  assert {
    condition     = length(aws_route_table.private.route) == 0
    error_message = "Private subnets must only reach the VPC and its endpoints."
  }
}

run "every_service_the_tasks_call_has_an_endpoint" {
  command = plan

  assert {
    condition     = toset(keys(aws_vpc_endpoint.gateway)) == toset(["dynamodb", "s3"]) && toset(keys(aws_vpc_endpoint.interface)) == toset(["ecr.api", "ecr.dkr", "logs", "sqs"])
    error_message = "Without NAT, every AWS service the tasks use needs a VPC endpoint."
  }
}

run "subnets_span_two_zones" {
  command = plan

  assert {
    condition     = length(distinct(aws_subnet.private[*].availability_zone)) == 2 && length(distinct(aws_subnet.public[*].availability_zone)) == 2
    error_message = "Subnets must be spread over two availability zones."
  }
}
