# Decision 11: bring this component's log group under Terraform, tagged and retention-capped.
#
# Lambda auto-creates its log group on first invocation, but with NEVER-EXPIRE retention and no
# tags. Declaring it here is what lets Terraform own both.
#
# This component's logs matter more than its single Lambda suggests: it is the one that runs
# unattended on a daily schedule in production, so when demo data stops appearing, this group is
# the only record of why. Its 900-second timeout also means a slow run looks identical to a hung
# one from the outside - the log is what distinguishes them.
#
# IMPORTANT for test and production: this group ALREADY EXISTS there, auto-created and unmanaged,
# so the first apply introducing this resource must be preceded by `terraform import` - CloudWatch
# rejects creating a log group whose name is taken. See deploy/import-log-groups.sh.
resource "aws_cloudwatch_log_group" "demo_data" {
  name              = "/aws/lambda/${aws_lambda_function.demo_data.function_name}"
  retention_in_days = var.log_retention_days

  tags = {
    Project     = var.project_name
    Component   = "mootmaker-demo-data"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}
