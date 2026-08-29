data "aws_caller_identity" "current" {}

locals {
  # Every AWS resource name derives from this instead of project_name directly, so multiple
  # environments (and this tool alongside mootmaker-api/mootmaker-webapp) can coexist in the same
  # AWS account without colliding.
  resource_prefix = "${var.environment}-${var.project_name}"

  lambda_jar_path = "${path.module}/../../impl/target/sample-data-generator.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null

  # database-reset's function name (now in ../mootmaker-admin-tools, split out from this repo on
  # 2026-08-29 by blast radius), computed the same deterministic way its own run.sh does
  # (environment + its project_name default "mootmaker-database-reset") rather than looked up via
  # that project's Terraform state - this project stays a standalone Terraform root with no
  # cross-project state coupling, the same reasoning the other *_table_name variables in
  # variables.tf document. That coupling now crosses a repository boundary but is otherwise
  # unchanged. This tool invokes that Lambda as the first step of every run (see
  # DatabaseResetInvoker), so database-reset must be deployed for this environment first.
  database_reset_function_name = "${var.environment}-mootmaker-database-reset"
  database_reset_function_arn  = "arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:function:${local.database_reset_function_name}"
}
