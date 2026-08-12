#!/usr/bin/env bash
# Deploys every tool in this project to the given environment, in the dependency order documented
# in README.md's "Deploy ordering": database-reset first (sample-data-generator invokes it
# directly, Lambda-to-Lambda, so it must already exist), then sample-data-generator, then
# sample-data-topup (best deployed after an environment has been seeded at least once), then
# database-repair last (no dependency on any other tool, so its position doesn't matter).
# NOTE: each tool's own deploy.sh runs `terraform apply -auto-approve`, creating real AWS
# resources in whatever account/credentials are active. Run this deliberately, not from
# automation.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy-all.sh <environment>   (e.g. test, production, or your own name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

for tool in database-reset sample-data-generator sample-data-topup database-repair; do
  echo "=== Deploying ${tool} to '${environment}' ==="
  ./"${tool}"/deploy.sh "${environment}"
done

echo "All tools deployed to '${environment}'."
