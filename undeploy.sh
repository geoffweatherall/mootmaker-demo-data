#!/usr/bin/env bash
# Destroys this environment's mootmaker-demo-data resources.
#
# Deliberately does NOT pass -auto-approve: unlike deploy.sh, this asks Terraform for interactive
# confirmation, so it cannot be run unattended by accident. Automation that genuinely intends this
# must pipe a confirmation in (`yes yes | ./undeploy.sh <environment>`).
#
# Destroying this component never destroys data: it removes the Lambda, its role and its schedule.
# The demo data it created stays in the environment's DynamoDB tables, which belong to
# mootmaker-api.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy.sh <environment>" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-demo-data/terraform.tfstate"
terraform -chdir=deploy/terraform destroy -var="environment=${environment}"
