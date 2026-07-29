data "aws_caller_identity" "current" {}

locals {
  # Every AWS resource name derives from this instead of project_name directly, so multiple
  # environments (and this tool alongside mootmaker-api/mootmaker-webapp) can coexist in the same
  # AWS account without colliding.
  resource_prefix = "${var.environment}-${var.project_name}"

  lambda_jar_path = "${path.module}/../../impl/target/database-reset.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null

  # mootmaker-api's Terraform outputs only expose table names (see its outputs.tf), not their
  # ARNs - this project needs the ARNs to scope its own IAM policy, so it builds them itself from
  # this account/region plus those names, the same shape AWS always uses for DynamoDB tables.
  rooms_table_arn                = "arn:aws:dynamodb:${var.aws_region}:${data.aws_caller_identity.current.account_id}:table/${var.rooms_table_name}"
  people_table_arn               = "arn:aws:dynamodb:${var.aws_region}:${data.aws_caller_identity.current.account_id}:table/${var.people_table_name}"
  meetings_table_arn             = "arn:aws:dynamodb:${var.aws_region}:${data.aws_caller_identity.current.account_id}:table/${var.meetings_table_name}"
  meeting_participants_table_arn = "arn:aws:dynamodb:${var.aws_region}:${data.aws_caller_identity.current.account_id}:table/${var.meeting_participants_table_name}"
}
