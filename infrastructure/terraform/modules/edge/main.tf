# Internet-facing ALB in the public subnets, with WAF in front. The ALB is the only public entry point.
# WAF does the per-IP rate limiting in AWS: behind the ALB the application sees the ALB's address, not the
# client's, so its own per-address limit is turned off there (RATE_LIMIT_IP_RPS=0).

locals {
  https = var.certificate_arn != ""
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Public HTTP(S) to the load balancer"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb" {
  for_each = toset(local.https ? ["80", "443"] : ["80"])

  security_group_id = aws_security_group.alb.id
  description       = "Public ${each.key}"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id = aws_security_group.alb.id
  description       = "To the api tasks inside the VPC"
  ip_protocol       = "tcp"
  from_port         = var.container_port
  to_port           = var.container_port
  cidr_ipv4         = data.aws_vpc.this.cidr_block
}

data "aws_vpc" "this" {
  id = var.vpc_id
}

resource "aws_lb" "this" {
  #checkov:skip=CKV_AWS_91:Access logs need an S3 bucket and its policy; WAF sampled requests and ALB metrics cover this assessment.
  #checkov:skip=CKV2_AWS_76:The attached ACL includes AWSManagedRulesKnownBadInputsRuleSet, which carries the Log4j rules; the check does not follow the association.
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = var.protect_data
}

resource "aws_lb_target_group" "api" {
  #checkov:skip=CKV_AWS_378:TLS ends at the ALB; ALB to task traffic stays inside the VPC on a port only the ALB can reach.
  name        = "${var.name}-api"
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip" # Fargate tasks register by IP
  vpc_id      = var.vpc_id

  # Matches the time Spring needs to finish in-flight requests on shutdown.
  deregistration_delay = 30

  # Shallow readiness: never calls DynamoDB, so a slow table cannot drain the whole fleet.
  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  #checkov:skip=CKV_AWS_2:With a certificate this listener only redirects to HTTPS; without one (no domain here) it serves HTTP.
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = local.https ? "redirect" : "forward"
    target_group_arn = local.https ? null : aws_lb_target_group.api.arn

    dynamic "redirect" {
      for_each = local.https ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = local.https ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_wafv2_web_acl" "this" {
  #checkov:skip=CKV2_AWS_31:Sampled requests and CloudWatch metrics are on; full WAF logging is billed per request and left for prod hardening.
  name  = var.name
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 0

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_requests_per_5_minutes_per_ip
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      metric_name                = "${var.name}-rate-limit"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  # Known bad inputs (Log4Shell-style payloads, exploitable paths). Cheap and low on false positives for a JSON API.
  rule {
    name     = "known-bad-inputs"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      metric_name                = "${var.name}-known-bad-inputs"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    metric_name                = var.name
    cloudwatch_metrics_enabled = true
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}
