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

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# The only AWS permissions this component needs. Everything else it does is outbound HTTPS: the
# Cognito token endpoint and the AppSync GraphQL endpoint. It never touches DynamoDB, never invokes
# another Lambda, and - unlike its predecessor sample-data-generator - has no reset path at all.
data "aws_iam_policy_document" "lambda_access" {
  statement {
    sid     = "ReadOwnCredentials"
    actions = ["ssm:GetParameters"]
    # Scoped to this environment's own parameter path, so a demo-data deployment can never read
    # another environment's credentials.
    resources = [
      "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_parameter_prefix}/*"
    ]
  }

  statement {
    sid     = "DecryptOwnClientSecret"
    actions = ["kms:Decrypt"]
    # The AWS-managed SSM key, not a customer-managed one: alias/aws/ssm is free, where a
    # customer-managed key is billed a flat $1/month per key per environment.
    resources = ["arn:aws:kms:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:key/*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${data.aws_region.current.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "lambda_access" {
  name   = "${local.resource_prefix}-access"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.lambda_access.json
}
