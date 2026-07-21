#!/usr/bin/env bash
# Resets the given mootmaker-api environment and populates it with sample data (40 people,
# 10 rooms, meetings across every business day from a week ago to seven weeks ahead). Talks to the
# mootmaker-api deployment of the given environment name in the sibling checkout (see the
# mootmaker project README for the multi-environment how-to).
# NOTE: this calls the `reset` mutation, which deletes all rooms and meetings (and any person not
# linked to a Cognito account) in the target environment - including production, which is itself
# a demo environment for this project.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./run.sh <environment>   (e.g. test, production, or your own name)" >&2
  exit 1
fi

if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

echo "Generating sample data for '${environment}'..."

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-tools)." >&2
  exit 1
fi

# Populates GRAPHQL_API_URL and the COGNITO_* variables from the deployed API's Terraform outputs
# for this environment (client_credentials M2M auth - see the API README's Authentication section).
source "${api_dir}/authenticate.sh" "${environment}"

mvn -q exec:java
