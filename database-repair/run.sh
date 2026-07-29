#!/usr/bin/env bash
# Invokes the deployed database-repair Lambda for the given environment (see deploy.sh) - runs
# maintenance repairs directly against that mootmaker-api environment's Cognito user pool and
# DynamoDB tables (see this project's README for what each repair does). Both repairs run on
# every invocation.
# NOTE: unlike sample-data-generator, this is safe to run against a real/production environment -
# it only ever creates missing Person records or reconciles the derived meeting-participants
# index, never deletes or modifies existing source-of-truth data - so there is deliberately no
# "refuse prod" check here.
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

dry_run="false"
if [[ "${2:-}" == "--dry-run" ]]; then
  dry_run="true"
fi

api_dir="../../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of mootmaker-tools)." >&2
  exit 1
fi

# Only need AWS_REGION here - the Lambda itself reads COGNITO_USER_POOL_ID/PEOPLE_TABLE_NAME/
# MEETINGS_TABLE_NAME/MEETING_PARTICIPANTS_TABLE_NAME from its own environment variables (set by
# deploy.sh), not from this shell.
source "${api_dir}/authenticate.sh" "${environment}" >/dev/null

function_name="${environment}-mootmaker-database-repair"

echo "Invoking ${function_name} in ${AWS_REGION}$( [[ "${dry_run}" == "true" ]] && echo " (dry run)" )..."

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
  --payload "{\"dryRun\": ${dry_run}}" \
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
