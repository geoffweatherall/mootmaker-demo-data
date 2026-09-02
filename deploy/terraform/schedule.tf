# Triggers this Lambda on a schedule (daily - see var.schedule_expression; enabled by default
# only for production, see local.schedule_enabled) with no
# per-invocation cost: EventBridge rules invoked on a schedule and targeting an AWS service
# directly (rather than publishing custom events) are not billed - the only cost is the Lambda
# invocation itself, which at this frequency falls entirely within Lambda's always-free tier
# (1M requests/month). This is the classic "CloudWatch Events" style scheduled rule rather than
# the newer, separate EventBridge Scheduler service - either is effectively free at this
# frequency, but this one is free with no caveats and needs less Terraform.
resource "aws_cloudwatch_event_rule" "scheduled_run" {
  name                = "${local.resource_prefix}-schedule"
  description         = "Triggers ${aws_lambda_function.demo_data.function_name} on a schedule"
  schedule_expression = var.schedule_expression
  state               = local.schedule_enabled ? "ENABLED" : "DISABLED"
}

resource "aws_cloudwatch_event_target" "demo_data_lambda" {
  rule = aws_cloudwatch_event_rule.scheduled_run.name
  arn  = aws_lambda_function.demo_data.arn

  # Without this, EventBridge sends its own event envelope (source, detail-type, time, ...) as the
  # payload. DemoData.Concerns would read that as "no toggles set" and default everything on, which
  # is the behaviour we want - but by accident rather than by contract. Sending an explicit empty
  # object says "all three concerns", deliberately, and makes the scheduled payload identical to
  # the one documented for a manual invoke.
  input = jsonencode({})
}

# EventBridge invokes targets directly via a resource-based Lambda permission (unlike a caller
# assuming an IAM role), scoped to just this rule - the same pattern cognito.tf uses for Cognito's
# PostConfirmation trigger, just with events.amazonaws.com as the principal instead.
resource "aws_lambda_permission" "eventbridge_invoke" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.demo_data.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_run.arn
}
