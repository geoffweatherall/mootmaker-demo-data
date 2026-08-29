#!/usr/bin/env bash
# Deploys both demo-data tools in this project to the given environment, in dependency order:
# sample-data-generator first (it resets the environment as the first step of every run), then
# sample-data-topup (best deployed after an environment has been seeded at least once).
#
# PREREQUISITE: sample-data-generator invokes database-reset Lambda-to-Lambda as the first step
# of every run - that tool now lives in ../mootmaker-admin-tools (split out on 2026-08-29 by
# blast radius; it can destroy data, this repo cannot). database-reset must already be deployed
# to this environment, or this script's first deploy will fail when sample-data-generator tries
# to invoke a Lambda that doesn't exist yet. See README.md.
#
# NOTE: each tool's own deploy.sh runs `terraform apply -auto-approve`, creating real AWS
# resources in whatever account/credentials are active. Run this deliberately, not from
# automation.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy-all.sh <environment>   (e.g. production, or your own name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

for tool in sample-data-generator sample-data-topup; do
  echo "=== Deploying ${tool} to '${environment}' ==="
  ./"${tool}"/deploy.sh "${environment}"
done

echo "Both demo-data tools deployed to '${environment}'."
