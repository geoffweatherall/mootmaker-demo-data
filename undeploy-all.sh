#!/usr/bin/env bash
# Undeploys both demo-data tools in this project from the given environment, in reverse of
# deploy-all.sh's order.
# NOTE: this is IRREVERSIBLE. Each tool's own undeploy.sh runs `terraform destroy` without
# -auto-approve, so this will prompt for interactive confirmation once per tool, in turn.
set -euo pipefail
cd "$(dirname "$0")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy-all.sh <environment>   (e.g. production, or your own name)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

for tool in sample-data-topup sample-data-generator; do
  echo "=== Undeploying ${tool} from '${environment}' ==="
  ./"${tool}"/undeploy.sh "${environment}"
done

echo "Both demo-data tools undeployed from '${environment}'."
