output "alb_dns_name" { value = aws_lb.main.dns_name }
output "cluster_name" { value = aws_ecs_cluster.main.name }
output "service_name" { value = aws_ecs_service.app.name }
output "log_group_name" { value = aws_cloudwatch_log_group.app.name }
