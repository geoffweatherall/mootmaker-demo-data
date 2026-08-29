data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda_exec" {
  name               = "${local.resource_prefix}-lambda-exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# The only other AWS permission this function needs: it invokes database-reset (in
# ../mootmaker-admin-tools, split out from this repo on 2026-08-29 by blast radius) as the first
# step of every run (see DatabaseResetInvoker), rather than through GraphQL. Everything
# else it does is an outbound HTTPS call (to the Cognito token endpoint and the AppSync GraphQL
# endpoint) - it never touches DynamoDB or any other AWS service directly.
data "aws_iam_policy_document" "invoke_database_reset" {
  statement {
    actions   = ["lambda:InvokeFunction"]
    resources = [local.database_reset_function_arn]
  }
}

resource "aws_iam_role_policy" "invoke_database_reset" {
  name   = "${local.resource_prefix}-invoke-database-reset"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.invoke_database_reset.json
}
