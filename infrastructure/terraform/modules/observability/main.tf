# Alarms on the signals that matter for a ticket sale. Each one names what it protects.

resource "aws_sns_topic" "alarms" {
  #checkov:skip=CKV_AWS_26:Encrypting with the aws/sns key would stop CloudWatch alarms from publishing (its key policy cannot grant CloudWatch); alarm texts are not sensitive.
  name = "${var.name}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email == "" ? 0 : 1

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alb = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }

  alarms = {
    api-5xx = {
      description = "The api answers 5xx: unexpected errors reach buyers."
      namespace   = "AWS/ApplicationELB"
      metric      = "HTTPCode_Target_5XX_Count"
      dimensions  = local.alb
      statistic   = "Sum"
      extended    = null
      period      = 60
      periods     = 3
      threshold   = 10
    }
    api-latency-p99 = {
      description = "p99 latency above 1 s: the sale feels broken even without errors."
      namespace   = "AWS/ApplicationELB"
      metric      = "TargetResponseTime"
      dimensions  = local.alb
      statistic   = null
      extended    = "p99"
      period      = 60
      periods     = 3
      threshold   = 1
    }
    api-unhealthy-tasks = {
      description = "A task fails its readiness check."
      namespace   = "AWS/ApplicationELB"
      metric      = "UnHealthyHostCount"
      dimensions  = local.alb
      statistic   = "Maximum"
      extended    = null
      period      = 60
      periods     = 2
      threshold   = 0
    }
    # The event's inventory item is the hot item (ADR 0001): throttling here is the first capacity limit.
    events-write-throttling = {
      description = "Writes to the events table (inventory) are throttled: the per-item limit is reached."
      namespace   = "AWS/DynamoDB"
      metric      = "WriteThrottleEvents"
      dimensions  = { TableName = var.table_names.events }
      statistic   = "Sum"
      extended    = null
      period      = 60
      periods     = 1
      threshold   = 0
    }
    orders-write-throttling = {
      description = "Writes to the orders table are throttled."
      namespace   = "AWS/DynamoDB"
      metric      = "WriteThrottleEvents"
      dimensions  = { TableName = var.table_names.orders }
      statistic   = "Sum"
      extended    = null
      period      = 60
      periods     = 1
      threshold   = 0
    }
    orders-queue-lag = {
      description = "Orders wait more than 2 minutes for the worker; holds may expire before processing."
      namespace   = "AWS/SQS"
      metric      = "ApproximateAgeOfOldestMessage"
      dimensions  = { QueueName = var.orders_queue_name }
      statistic   = "Maximum"
      extended    = null
      period      = 60
      periods     = 2
      threshold   = 120
    }
    orders-dlq-not-empty = {
      description = "A message failed 3 times and went to the dead-letter queue; it needs a look and a redrive."
      namespace   = "AWS/SQS"
      metric      = "ApproximateNumberOfMessagesVisible"
      dimensions  = { QueueName = var.orders_dlq_name }
      statistic   = "Maximum"
      extended    = null
      period      = 300
      periods     = 1
      threshold   = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "this" {
  for_each = local.alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  dimensions          = each.value.dimensions
  statistic           = each.value.statistic
  extended_statistic  = each.value.extended
  period              = each.value.period
  evaluation_periods  = each.value.periods
  comparison_operator = "GreaterThanThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}
