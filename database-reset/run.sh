#!/usr/bin/env bash
# Invokes the deployed database-reset Lambda for the given environment (see deploy.sh) - deletes
# all stored rooms and meetings, and every person except those linked to a Cognito account, from
# that mootmaker-api environment.
# NOTE: this deletes data - including production, which is itself a demo environment for this
# project, so that's expected. People linked to a real Cognito account (see the API README's
# "Reset and real user accounts" section) are always preserved.
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

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-tools)." >&2
  exit 1
fi

# Only need AWS_REGION here - the Lambda itself reads ROOMS_TABLE_NAME/PEOPLE_TABLE_NAME/
# MEETINGS_TABLE_NAME/MEETING_PARTICIPANTS_TABLE_NAME from its own environment variables (set by
# deploy.sh), not from this shell.
source "${api_dir}/authenticate.sh" "${environment}" >/dev/null

function_name="${environment}-mootmaker-database-reset"

echo "Invoking ${function_name} in ${AWS_REGION} (this deletes rooms/meetings/unlinked-people in '${environment}')..."

response_file="$(mktemp)"
trap 'rm -f "${response_file}"' EXIT

# --cli-read-timeout comfortably exceeds the Lambda's own 300s configured timeout (see
# deploy/terraform/lambda.tf) so the CLI never times out first and misleadingly reports failure
# for a run that was actually still in progress.
invoke_result="$(aws lambda invoke \
  --function-name "${function_name}" \
  --region "${AWS_REGION}" \
  --cli-read-timeout 330 \
  --log-type Tail \
  --payload '{}' \
  --cli-binary-format raw-in-base64-out \
  --output json \
  "${response_file}")"

echo
echo "--- Lambda output (last 4KB; see CloudWatch Logs /aws/lambda/${function_name} for the full run) ---"
jq -r '.LogResult' <<<"${invoke_result}" | base64 -d
echo "---"

function_error="$(jq -r '.FunctionError // empty' <<<"${invoke_result}")"
if [[ -n "${function_error}" ]]; then
  echo "Lambda invocation failed (${function_error}):" >&2
  cat "${response_file}" >&2
  exit 1
fi

echo "Done: $(cat "${response_file}")"
