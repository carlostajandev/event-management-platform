output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "target_group_arn" {
  value = aws_lb_target_group.api.arn
}

output "alb_arn_suffix" {
  description = "Dimension value for ALB CloudWatch metrics and request-count scaling."
  value       = aws_lb.this.arn_suffix
}

output "target_group_arn_suffix" {
  value = aws_lb_target_group.api.arn_suffix
}

output "listener_arn" {
  description = "Listener that forwards to the api; the service depends on it existing."
  value       = local.https ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
}
