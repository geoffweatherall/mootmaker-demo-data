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

  # Two concurrent runs could both observe 30 rooms and both create 10. Rather than add locking,
  # let Lambda enforce it: a second overlapping invocation is throttled outright, visibly (a 429
  # for a synchronous caller, EventBridge's own retry for the scheduled one). Costs nothing and
  # cannot be forgotten. This bounds whole invocations; DemoData's MAX_CONCURRENT_REQUESTS bounds
  # parallelism *within* one run and is unaffected.
  reserved_concurrent_executions = 1

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
}
