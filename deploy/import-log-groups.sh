#!/usr/bin/env bash
# Imports this environment's ALREADY-EXISTING log group into Terraform state, so the first apply
# that introduces deploy/terraform/logs.tf does not fail.
#
# Why (design Rollout step 12): Lambda auto-creates its log group on first invocation, unmanaged
# and with never-expire retention. CloudWatch rejects creating a group whose name is taken, so on
# any environment that has ever run - test and production - a plain apply fails. Importing first
# hands Terraform the existing group instead.
#
# A FRESH ephemeral environment needs none of this; the script skips anything absent, so running
# it there is harmless. Idempotent: a group already in state is skipped.
#
# Usage: ./deploy/import-log-groups.sh <environment>
set -euo pipefail
cd "$(dirname "$0")/.."

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy/import-log-groups.sh <environment>" >&2
  exit 1
fi

export TF_DATA_DIR=".terraform-${environment}"
terraform -chdir=deploy/terraform init \
  -backend-config=backend.hcl \
  -backend-config="key=${environment}/mootmaker-demo-data/terraform.tfstate" \
  -input=false >/dev/null

name="/aws/lambda/${environment}-mootmaker-demo-data"
address="aws_cloudwatch_log_group.demo_data"

if terraform -chdir=deploy/terraform state show "${address}" >/dev/null 2>&1; then
  echo "already in state, skipping: ${name}"
elif ! aws logs describe-log-groups --log-group-name-prefix "${name}" \
    --query "logGroups[?logGroupName=='${name}'] | length(@)" --output text | grep -q '^1$'; then
  echo "does not exist in AWS, nothing to import: ${name}"
else
  echo "importing ${name}"
  terraform -chdir=deploy/terraform import \
    -var="environment=${environment}" \
    "${address}" "${name}"
fi

echo "Done. A plain ./deploy.sh ${environment} can now apply logs.tf safely."
