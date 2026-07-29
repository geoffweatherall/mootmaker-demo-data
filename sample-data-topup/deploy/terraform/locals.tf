locals {
  # Every AWS resource name derives from this instead of project_name directly, so multiple
  # environments (and this tool alongside mootmaker-api/mootmaker-webapp) can coexist in the same
  # AWS account without colliding.
  resource_prefix = "${var.environment}-${var.project_name}"

  lambda_jar_path = "${path.module}/../../impl/target/sample-data-topup.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null
}
