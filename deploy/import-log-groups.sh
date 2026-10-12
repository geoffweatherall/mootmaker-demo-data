#!/usr/bin/env bash
# Imports this environment's log group into Terraform state if it exists but is not there, so an
# apply does not fail with ResourceAlreadyExistsException.
#
# Lambda auto-creates its log group on first invocation if none exists, with never-expire
# retention. Terraform normally creates it first (see deploy/terraform/logs.tf), so this is needed
# only if the group was ever created outside Terraform. It skips a group that does not exist, so it
# is safe to run on any environment. Idempotent: a group already in state is skipped.
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
