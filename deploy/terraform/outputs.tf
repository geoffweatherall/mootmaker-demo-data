output "function_name" {
  description = "Name of the deployed Lambda function. Deterministic (<environment>-mootmaker-demo-data), so callers can compute it rather than reading this - it exists for manual verification (`terraform output function_name`, or the AWS Console)."
  value       = aws_lambda_function.demo_data.function_name
}

output "schedule_rule_name" {
  description = "Name of the EventBridge rule triggering scheduled runs - look this up in the CloudWatch/EventBridge console to check next/last invocation times."
  value       = aws_cloudwatch_event_rule.scheduled_run.name
}

output "schedule_enabled" {
  description = "Whether scheduled runs are actually active in this environment (true for production by default, false elsewhere)."
  value       = local.schedule_enabled
}
