#!/usr/bin/env bash
# Runs maintenance repairs directly against a deployed mootmaker-api environment's Cognito
# user pool and DynamoDB tables (see this project's README for what each repair does). Talks to
# the mootmaker-api deployment of the given environment name in the sibling checkout (see the
# mootmaker project README for the multi-environment how-to).
# NOTE: unlike sample-data-generator, this is safe to run against a real/production environment -
# it only ever creates missing Person records, never deletes or modifies existing data - so there
# is deliberately no "refuse prod" check here.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./run.sh <environment> [--dry-run]   (e.g. test, production, or your own name)" >&2
  exit 1
fi

if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

echo "Running database repairs against '${environment}'..."

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-tools)." >&2
  exit 1
fi

# Populates COGNITO_USER_POOL_ID, AWS_REGION, PEOPLE_TABLE_NAME, MEETINGS_TABLE_NAME and
# MEETING_PARTICIPANTS_TABLE_NAME from the deployed API's Terraform outputs for this environment.
source "${api_dir}/authenticate.sh" "${environment}"

mvn -q exec:java -Dexec.args="${2:-}"
