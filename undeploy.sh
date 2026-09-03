#!/usr/bin/env bash
# Destroys this environment's mootmaker-demo-data resources.
#
# Deliberately does NOT pass -auto-approve by default: unlike deploy.sh, this asks Terraform for
# interactive confirmation, so it cannot be run unattended by accident.
#
# --yes passes -auto-approve, for automation that has no stdin to answer the prompt with (the
# release pipeline's ephemeral acceptance environments, and the scheduled ephemeral sweep - see
# mootmaker/designs/ci-cd-pipeline.md Rollout steps 6 and 11). Use this rather than piping a
# confirmation in: `yes yes | ./undeploy.sh <environment>` answers whatever it is pointed at,
# which defeats the safeguard instead of narrowing it.
#
# Non-interactive mode is deliberately NARROWER than interactive mode, not just quieter: with
# --yes, "production" and "test" are refused outright, since those change through the release
# pipeline (Decision 6), never through an unattended undeploy.
#
# Destroying this component never destroys data: it removes the Lambda, its role and its schedule.
# The demo data it created stays in the environment's DynamoDB tables, which belong to
# mootmaker-api.
set -euo pipefail
cd "$(dirname "$0")"

assume_yes=0
args=()
for arg in "$@"; do
  if [[ "${arg}" == "--yes" ]]; then
    assume_yes=1
  else
    args+=("${arg}")
  fi
done
set -- "${args[@]+"${args[@]}"}"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy.sh <environment> [--yes]" >&2
  exit 1
fi
if [[ "${assume_yes}" == "1" && ( "${environment}" == "production" || "${environment}" == "test" ) ]]; then
  echo "Refusing to undeploy '${environment}' with --yes: standing environments are never destroyed unattended. Re-run without --yes and confirm the prompt if you really mean it." >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi

export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-demo-data/terraform.tfstate"
destroy_args=(-var="environment=${environment}")
if [[ "${assume_yes}" == "1" ]]; then
  destroy_args+=(-auto-approve)
fi
terraform -chdir=deploy/terraform destroy "${destroy_args[@]}"
