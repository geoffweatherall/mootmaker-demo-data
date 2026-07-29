resource "aws_lambda_function" "topup_sample_data" {
  function_name    = local.resource_prefix
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.tools.sampledatatopup.TopUpSampleDataHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512

  # A run does up to 3 concurrent reads (rooms/people/existing meetings) followed by up to ~600
  # createMeeting calls in the worst case (a freshly-seeded environment with every weekday in the
  # 6-week window still empty), spread across up to 8 concurrent requests at a time (see
  # SampleDataTopUp.runInParallel) - comfortably under a minute in practice, and every subsequent
  # weekly run only tops up the handful of newly-uncovered days at the far end of the window. A
  # Lambda invocation can run for at most 15 minutes regardless of this setting, so parallelising
  # the calls (rather than raising this number) is what actually keeps a run inside that hard
  # ceiling as stored data volume grows.
  timeout = 300

  environment {
    variables = {
      GRAPHQL_API_URL            = var.graphql_api_url
      COGNITO_TOKEN_URL          = var.cognito_token_url
      COGNITO_TEST_CLIENT_ID     = var.cognito_test_client_id
      COGNITO_TEST_CLIENT_SECRET = var.cognito_test_client_secret
      COGNITO_TEST_SCOPE         = var.cognito_test_scope
    }
  }
}
