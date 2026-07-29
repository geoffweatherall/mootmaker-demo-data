#!/usr/bin/env bash
# Builds the Lambda jar and deploys database-reset to AWS via Terraform, wired up to target the
# mootmaker-api deployment of the given environment name in the sibling checkout (see the
# mootmaker project README for the multi-environment how-to).
# NOTE: `terraform apply -auto-approve` creates real AWS resources in whatever account/credentials
# are active. Run this deliberately, not from automation.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy.sh <environment>   (e.g. test, production, or your own name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

echo "Deploying database-reset, targeting mootmaker-api environment '${environment}'..."

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-tools)." >&2
  exit 1
fi

# Populates ROOMS_TABLE_NAME, PEOPLE_TABLE_NAME, MEETINGS_TABLE_NAME and
# MEETING_PARTICIPANTS_TABLE_NAME from the target environment's Terraform outputs.
source "${api_dir}/authenticate.sh" "${environment}"

mvn -f impl/pom.xml clean package

# Isolates this environment's Terraform provider cache/backend pointer from other environments,
# so deploying "test" and "production" from the same checkout (even concurrently) can't
# cross-contaminate each other.
export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-tools-database-reset/terraform.tfstate"
terraform -chdir=deploy/terraform apply -auto-approve \
  -var="environment=${environment}" \
  -var="aws_region=${AWS_REGION}" \
  -var="rooms_table_name=${ROOMS_TABLE_NAME}" \
  -var="people_table_name=${PEOPLE_TABLE_NAME}" \
  -var="meetings_table_name=${MEETINGS_TABLE_NAME}" \
  -var="meeting_participants_table_name=${MEETING_PARTICIPANTS_TABLE_NAME}"
