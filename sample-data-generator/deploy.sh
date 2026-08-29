#!/usr/bin/env bash
# Builds the Lambda jar and deploys the sample data generator to AWS via Terraform, wired up to
# target the mootmaker-api deployment of the given environment name in the sibling checkout (see
# the mootmaker project README for the multi-environment how-to).
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

echo "Deploying sample-data-generator, targeting mootmaker-api environment '${environment}'..."

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-demo-data)." >&2
  exit 1
fi

# Populates GRAPHQL_API_URL and the COGNITO_* variables from the target environment's Terraform
# outputs - the same client_credentials M2M auth mootmaker-api's own acceptance tests use.
source "${api_dir}/authenticate.sh" "${environment}"

mvn -f impl/pom.xml clean package

# Isolates this environment's Terraform provider cache/backend pointer from other environments,
# so deploying "test" and "production" from the same checkout (even concurrently) can't
# cross-contaminate each other.
export TF_DATA_DIR=".terraform-${environment}"

# Passed via TF_VAR_ rather than -var so the client secret never appears in `ps` output.
export TF_VAR_cognito_test_client_secret="${COGNITO_TEST_CLIENT_SECRET}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-tools-sample-data-generator/terraform.tfstate"
terraform -chdir=deploy/terraform apply -auto-approve \
  -var="environment=${environment}" \
  -var="graphql_api_url=${GRAPHQL_API_URL}" \
  -var="cognito_token_url=${COGNITO_TOKEN_URL}" \
  -var="cognito_test_client_id=${COGNITO_TEST_CLIENT_ID}" \
  -var="cognito_test_scope=${COGNITO_TEST_SCOPE}"
