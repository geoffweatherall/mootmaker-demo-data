#!/usr/bin/env bash
# Builds the Lambda jar and deploys mootmaker-demo-data to AWS via Terraform, targeting the
# mootmaker-api deployment of the given environment name.
#
# Unlike its predecessors, this needs NOTHING from mootmaker-api's Terraform state: the GraphQL
# URL, Cognito token endpoint, client id/secret and scopes are read at runtime from SSM Parameter
# Store, at paths derived from the environment name (mootmaker-api writes them - see its
# deploy/terraform/demo-data-credentials.tf). mootmaker-api must therefore be deployed to this
# environment FIRST, or the Lambda will deploy successfully and then fail on its first invocation
# with a missing-parameter error.
#
# NOTE: `terraform apply -auto-approve` creates real AWS resources in whatever account/credentials
# are active.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy.sh <environment>   (e.g. production, or an ephemeral environment name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

echo "Deploying mootmaker-demo-data to '${environment}'..."

mvn -f impl/pom.xml clean package

# Isolates this environment's Terraform provider cache/backend pointer from other environments, so
# deploying two environments from the same checkout (even concurrently) can't cross-contaminate.
export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-demo-data/terraform.tfstate"
terraform -chdir=deploy/terraform apply -auto-approve -var="environment=${environment}"

echo "mootmaker-demo-data deployed to '${environment}'."
echo "It does not run on deploy - invoke it explicitly (see README.md), or wait for the schedule where enabled."
