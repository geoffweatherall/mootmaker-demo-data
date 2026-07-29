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

# Grants exactly what this project's README used to ask a developer to bring their own credentials
# for (ListUsers on the user pool; Query/PutItem on People plus its cognitoSub-index; Scan on
# Meetings; Scan/PutItem/DeleteItem on meeting-participants) - now scoped to this function's own
# role instead of relying on whatever ambient AWS credentials happened to be active.
data "aws_iam_policy_document" "repair_access" {
  statement {
    sid       = "ListCognitoUsers"
    actions   = ["cognito-idp:ListUsers"]
    resources = [local.cognito_user_pool_arn]
  }

  statement {
    sid     = "PeopleTableAccess"
    actions = ["dynamodb:Query", "dynamodb:PutItem"]
    resources = [
      local.people_table_arn,
      # cognitoSub-index is a separate resource from the table itself as far as IAM is concerned.
      "${local.people_table_arn}/index/*",
    ]
  }

  statement {
    sid       = "MeetingsTableRead"
    actions   = ["dynamodb:Scan"]
    resources = [local.meetings_table_arn]
  }

  statement {
    sid       = "MeetingParticipantsTableAccess"
    actions   = ["dynamodb:Scan", "dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [local.meeting_participants_table_arn]
  }
}

resource "aws_iam_role_policy" "repair_access" {
  name   = "${local.resource_prefix}-repair-access"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.repair_access.json
}
