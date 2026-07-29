# Triggers this Lambda on a schedule (default weekly - see var.schedule_expression) with no
# per-invocation cost: EventBridge rules invoked on a schedule and targeting an AWS service
# directly (rather than publishing custom events) are not billed - the only cost is the Lambda
# invocation itself, which at this frequency falls entirely within Lambda's always-free tier
# (1M requests/month). This is the classic "CloudWatch Events" style scheduled rule rather than
# the newer, separate EventBridge Scheduler service - either is effectively free at this
# frequency, but this one is free with no caveats and needs less Terraform.
resource "aws_cloudwatch_event_rule" "weekly_topup" {
  name                = "${local.resource_prefix}-weekly"
  description         = "Triggers ${aws_lambda_function.topup_sample_data.function_name} on a schedule"
  schedule_expression = var.schedule_expression
  state               = var.schedule_enabled ? "ENABLED" : "DISABLED"
}

resource "aws_cloudwatch_event_target" "topup_lambda" {
  rule = aws_cloudwatch_event_rule.weekly_topup.name
  arn  = aws_lambda_function.topup_sample_data.arn
}

# EventBridge invokes targets directly via a resource-based Lambda permission (unlike a caller
# assuming an IAM role), scoped to just this rule - the same pattern cognito.tf uses for Cognito's
# PostConfirmation trigger, just with events.amazonaws.com as the principal instead.
resource "aws_lambda_permission" "eventbridge_invoke" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.topup_sample_data.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.weekly_topup.arn
}
