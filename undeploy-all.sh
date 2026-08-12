#!/usr/bin/env bash
# Undeploys every tool in this project from the given environment, in the reverse of
# deploy-all.sh's dependency order: database-repair, then sample-data-topup, then
# sample-data-generator, then database-reset last (sample-data-generator depends on it - see
# README.md's "Deploy ordering").
# NOTE: this is IRREVERSIBLE. Each tool's own undeploy.sh runs `terraform destroy` without
# -auto-approve, so this will prompt for interactive confirmation once per tool, in turn.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy-all.sh <environment>   (e.g. test, production, or your own name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

for tool in database-repair sample-data-topup sample-data-generator database-reset; do
  echo "=== Undeploying ${tool} from '${environment}' ==="
  ./"${tool}"/undeploy.sh "${environment}"
done

echo "All tools undeployed from '${environment}'."
