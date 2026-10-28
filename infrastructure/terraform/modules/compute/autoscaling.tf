resource "aws_appautoscaling_target" "this" {
  for_each = {
    api    = { service = aws_ecs_service.api.name, min = var.api.min_tasks, max = var.api.max_tasks }
    worker = { service = aws_ecs_service.worker.name, min = var.worker.min_tasks, max = var.worker.max_tasks }
  }

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${each.value.service}"
  min_capacity       = each.value.min
  max_capacity       = each.value.max
}

# api: requests per task is the direct signal; CPU covers expensive requests.
resource "aws_appautoscaling_policy" "api_requests" {
  name               = "${var.name}-api-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["api"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["api"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["api"].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.api.requests_per_target
    scale_out_cooldown = 60
    scale_in_cooldown  = 300

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = var.alb_request_count_label
    }
  }
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${var.name}-api-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["api"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["api"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["api"].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_out_cooldown = 60
    scale_in_cooldown  = 300

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# worker: queue depth. Never below min_tasks: the worker also runs the outbox relay and the expiry job.
resource "aws_appautoscaling_policy" "worker_out" {
  name               = "${var.name}-worker-backlog-out"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.this["worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["worker"].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = var.worker.backlog_scale_out * 9
      scaling_adjustment          = 1
    }

    step_adjustment {
      metric_interval_lower_bound = var.worker.backlog_scale_out * 9
      scaling_adjustment          = 3
    }
  }
}

resource "aws_appautoscaling_policy" "worker_in" {
  name               = "${var.name}-worker-backlog-in"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.this["worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["worker"].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 300
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = -1
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_backlog_high" {
  alarm_name          = "${var.name}-worker-backlog-high"
  alarm_description   = "Scales the worker out when orders wait in the queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.orders_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.worker.backlog_scale_out
  alarm_actions       = [aws_appautoscaling_policy.worker_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "worker_backlog_low" {
  alarm_name          = "${var.name}-worker-backlog-low"
  alarm_description   = "Scales the worker in after the queue has been nearly empty for 15 minutes."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.orders_queue_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 10
  alarm_actions       = [aws_appautoscaling_policy.worker_in.arn]
}
