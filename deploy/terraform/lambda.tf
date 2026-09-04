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

  # Off by default, and that is an accepted risk rather than an outstanding problem (Geoff,
  # 2026-09-02).
  #
  # The theoretical exposure: two runs overlapping could both observe 30 rooms and both create 10.
  # The guard would be structural - reserve concurrency 1, so Lambda throttles the second
  # invocation outright and visibly. This account cannot express that: its total Lambda concurrency
  # quota is 10 (not the usual 1000) and AWS refuses any reservation leaving fewer than 10
  # unreserved, so every value is rejected, not just this one.
  #
  # Why that is fine here: overlap needs a manual invoke to land inside the few seconds a scheduled
  # run is active, once a day. If it ever happened the result is a few extra rooms or people in a
  # demo environment - not corruption, not data loss - and the next run is a no-op again because
  # every concern is defined by its target rather than by what it last did. The variable stays so
  # that setting it to 1 is the whole fix if the quota is ever raised, but nothing is waiting on
  # that.
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
