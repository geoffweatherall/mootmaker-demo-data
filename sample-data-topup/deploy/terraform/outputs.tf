output "function_name" {
  description = "Name of the deployed Lambda function - matches what run.sh computes itself, so this output exists only for manual verification (e.g. `terraform output function_name` or in the AWS Console)."
  value       = aws_lambda_function.topup_sample_data.function_name
}

output "schedule_rule_name" {
  description = "Name of the EventBridge rule triggering weekly runs - look this up in the CloudWatch/EventBridge console to check next/last invocation times."
  value       = aws_cloudwatch_event_rule.weekly_topup.name
}
