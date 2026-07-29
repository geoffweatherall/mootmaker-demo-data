#!/usr/bin/env bash
# Invokes the deployed sample-data-topup Lambda for the given environment on demand (see
# deploy.sh) - the same thing its weekly EventBridge schedule does automatically. Adds sample
# meetings to any weekday in the next 6 weeks that currently has none; never resets or deletes
# anything, so this is safe to run against production or any shared environment at any time.
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

# Only need AWS_REGION here - the Lambda itself reads GRAPHQL_API_URL/COGNITO_* from its own
# environment variables (set by deploy.sh), not from this shell.
source "${api_dir}/authenticate.sh" "${environment}" >/dev/null

function_name="${environment}-mootmaker-sample-data-topup"

echo "Invoking ${function_name} in ${AWS_REGION}..."

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
