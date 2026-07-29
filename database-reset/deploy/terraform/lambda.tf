resource "aws_lambda_function" "database_reset" {
  function_name    = local.resource_prefix
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.tools.databasereset.DatabaseResetHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512

  # The three deletion passes (rooms, unlinked people, meetings+participants) run concurrently,
  # and each spreads its own per-item DeleteItem calls across a bounded thread pool (see
  # DatabaseResetHandler), which is what actually keeps a run comfortably inside a Lambda
  # invocation's 15-minute hard ceiling as stored data volume grows, rather than this timeout.
  timeout = 300

  # AWS_REGION is deliberately not set here - it's a reserved Lambda runtime variable Terraform
  # can't assign (the platform sets it automatically to wherever the function is actually
  # deployed), which is exactly the region DatabaseResetHandler needs: this function only ever
  # talks to DynamoDB in its own region.
  environment {
    variables = {
      ROOMS_TABLE_NAME                = var.rooms_table_name
      PEOPLE_TABLE_NAME               = var.people_table_name
      MEETINGS_TABLE_NAME             = var.meetings_table_name
      MEETING_PARTICIPANTS_TABLE_NAME = var.meeting_participants_table_name
    }
  }
}
