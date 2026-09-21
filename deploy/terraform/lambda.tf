resource "aws_lambda_function" "demo_data" {
  function_name    = local.resource_prefix
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.demodata.DemoDataHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512

  # The AWS maximum, not a smaller guessed number - see the design's "Technical considerations".
  # Lambda bills actual execution duration, so a high ceiling costs nothing on the short runs that
  # are the norm, and only matters on the day a run is legitimately slow (a full seed of a fresh
  # environment: ~35 business days of meetings, plus 40 people and 10 rooms). What actually keeps a
  # run inside the ceiling as stored data grows is DemoData's bounded parallelism
  # (MAX_CONCURRENT_REQUESTS = 8), not a lower timeout.
  #
  # NOTE: every caller's own client-side timeout has to match this, or a legitimately long run gets
  # reported as a failure while this function keeps running and completes regardless. The AWS CLI
  # defaults to a 60-second read timeout - see the README's documented `--cli-read-timeout 900`.
  timeout = 900

  # Reserved at 1 (mootmaker-demo-data#27), making the theoretical overlap below structurally
  # impossible rather than merely unlikely: two runs racing could both observe 30 rooms and both
  # create 10. Lambda now throttles a second concurrent invocation outright and visibly instead of
  # letting it race the first.
  #
  # This was impossible until 2026-09-07 (Geoff, 2026-09-02, when it was still an accepted risk):
  # this account's total Lambda concurrency quota was 10 (not the usual 1000), and AWS refuses any
  # reservation leaving fewer than 10 unreserved, so every value was rejected, not just this one.
  # The quota is now 1,000, so the reservation is settable and running unreserved became a choice
  # rather than a constraint - see variables.tf.
  #
  # Does not throttle DemoData's own internal fan-out: MAX_CONCURRENT_REQUESTS there bounds
  # parallel GraphQL calls this Lambda makes OUT per invocation, not invocations of this Lambda
  # itself - a single invocation still makes up to 8 concurrent AppSync calls under this
  # reservation.
  reserved_concurrent_executions = var.reserved_concurrency

  # No credentials here, deliberately: the client id/secret and endpoints are read from SSM at
  # runtime (see SsmSecrets). Only the environment name - which is not a secret and is needed to
  # build the parameter paths - and the targets are passed in. AWS_REGION is set by Lambda itself.
  environment {
    variables = {
      ENVIRONMENT   = var.environment
      TARGET_PEOPLE = tostring(var.target_people)
      TARGET_ROOMS  = tostring(var.target_rooms)
      DAYS_IN_PAST  = tostring(var.days_in_past)
      WEEKS_AHEAD   = tostring(var.weeks_ahead)
    }
  }

  # The log group must exist BEFORE this function is invoked, or Lambda auto-creates its
  # own and collides with Terraform's. See logs.tf.
  depends_on = [aws_cloudwatch_log_group.demo_data]
}
