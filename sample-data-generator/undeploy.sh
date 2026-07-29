#!/usr/bin/env bash
# Destroys the AWS resources created by deploy.sh for the given environment: the Lambda function
# and its execution role. Does not touch the target mootmaker-api environment itself.
#
# NOTE: this is IRREVERSIBLE. Terraform will prompt for interactive confirmation before deleting
# anything; this script intentionally does not pass -auto-approve.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy.sh <environment>   (e.g. test, production, or your own name)" >&2
  exit 1
fi

echo "Undeploying sample-data-generator environment '${environment}'..."

export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-tools-sample-data-generator/terraform.tfstate"
terraform -chdir=deploy/terraform destroy -var="environment=${environment}"
