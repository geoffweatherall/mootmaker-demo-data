locals {
  # Every AWS resource name derives from this instead of project_name directly, so multiple
  # environments (and this component alongside mootmaker-api/mootmaker-webapp) can coexist in the
  # same AWS account without colliding.
  resource_prefix = "${var.environment}-${var.project_name}"

  # Only production runs on a schedule by default - see var.schedule_enabled.
  schedule_enabled = var.schedule_enabled != null ? var.schedule_enabled : var.environment == "production"

  # Where mootmaker-api publishes this component's credentials and endpoints. Derived from the
  # environment name alone, deliberately: neither project reads the other's Terraform state.
  ssm_parameter_prefix = "/mootmaker/${var.environment}/demo-data"

  lambda_jar_path = "${path.module}/../../impl/target/demo-data.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null
}
