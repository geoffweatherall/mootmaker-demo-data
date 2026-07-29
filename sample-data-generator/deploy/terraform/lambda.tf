resource "aws_lambda_function" "generate_sample_data" {
  function_name    = local.resource_prefix
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.tools.sampledata.GenerateSampleDataHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512

  # A full run does a reset plus ~700 GraphQL calls (40 people + 10 rooms + ~650 meetings), spread
  # across up to 8 concurrent requests at a time (see SampleDataGenerator.runInParallel) - well
  # under a minute in practice, but a Lambda invocation can run for at most 15 minutes regardless
  # of this setting, so parallelising the calls (rather than raising this number) is what actually
  # keeps a run inside that hard ceiling as sample data volume grows.
  timeout = 300

  # AWS_REGION is deliberately not set here - it's a reserved Lambda runtime variable Terraform
  # can't assign (the platform sets it automatically to wherever the function is actually
  # deployed), which is exactly the region DatabaseResetInvoker needs: this function only ever
  # invokes database-reset in its own region.
  environment {
    variables = {
      GRAPHQL_API_URL              = var.graphql_api_url
      COGNITO_TOKEN_URL            = var.cognito_token_url
      COGNITO_TEST_CLIENT_ID       = var.cognito_test_client_id
      COGNITO_TEST_CLIENT_SECRET   = var.cognito_test_client_secret
      COGNITO_TEST_SCOPE           = var.cognito_test_scope
      DATABASE_RESET_FUNCTION_NAME = local.database_reset_function_name
    }
  }
}
