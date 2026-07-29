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

# Only Scan + DeleteItem, on exactly the four tables this deletes from - narrower than the
# permissions mootmaker-api's own shared Lambda role used to need for this (which also had to
# cover PutItem/Query/etc. for every other resolver sharing that role). Now that reset is its own
# Lambda with its own role, it's scoped to exactly what it does.
data "aws_iam_policy_document" "reset_access" {
  statement {
    sid     = "ResetTableAccess"
    actions = ["dynamodb:Scan", "dynamodb:DeleteItem"]
    resources = [
      local.rooms_table_arn,
      local.people_table_arn,
      local.meetings_table_arn,
      local.meeting_participants_table_arn,
    ]
  }
}

resource "aws_iam_role_policy" "reset_access" {
  name   = "${local.resource_prefix}-reset-access"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.reset_access.json
}
