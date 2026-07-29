resource "aws_lambda_function" "database_repair" {
  function_name    = local.resource_prefix
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.tools.databaserepair.DatabaseRepairHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512

  # The two repairs run concurrently, and each spreads its own per-item AWS calls across a bounded
  # thread pool (see DatabaseRepairHandler), which is what actually keeps a run comfortably inside
  # a Lambda invocation's 15-minute hard ceiling as the number of users/meetings grows, rather than
  # this timeout itself.
  timeout = 300

  # AWS_REGION is deliberately not set here - it's a reserved Lambda runtime variable Terraform
  # can't assign (the platform sets it automatically to wherever the function is actually
  # deployed), which is exactly the region DatabaseRepairHandler needs: this function only ever
  # talks to Cognito/DynamoDB in its own region.
  environment {
    variables = {
      COGNITO_USER_POOL_ID            = var.cognito_user_pool_id
      PEOPLE_TABLE_NAME               = var.people_table_name
      MEETINGS_TABLE_NAME             = var.meetings_table_name
      MEETING_PARTICIPANTS_TABLE_NAME = var.meeting_participants_table_name
    }
  }
}
