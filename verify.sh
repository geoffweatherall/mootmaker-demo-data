#!/usr/bin/env bash
# Builds and runs the /verify acceptance tests against a deployed environment.
#
# These assert the INVARIANTS generated demo data must satisfy (no room double-booked, nobody in
# two meetings at once, nothing outside business hours, a second run changes nothing) rather than
# exact values - see testing-strategy.md.
#
# PREREQUISITE: mootmaker-api AND mootmaker-demo-data must both be deployed to this environment.
# The suite resets the environment via mootmaker-api's database-reset Lambda and then seeds it with
# a real demo-data run, so it needs both components live. It is destructive: never point it at
# production.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./verify.sh <environment>   (an ephemeral environment name)" >&2
  exit 1
fi
if [[ "${environment}" == "production" ]]; then
  echo "Refusing to run: this suite resets the environment before seeding it, which would destroy production's data." >&2
  exit 1
fi

api_dir="../mootmaker-api"
if [[ ! -f "${api_dir}/authenticate.sh" ]]; then
  echo "Expected to find the mootmaker-api checkout at ${api_dir} (as a sibling of this directory)." >&2
  exit 1
fi

# The suite reads data back through GraphQL as the acceptance-test client - deliberately a
# different identity from demo-data's own, since it is the harness rather than the thing under
# test. Unlike deploy.sh, which needs nothing from mootmaker-api, a test harness reading that
# project's outputs is the same thing its own verify.sh does.
source "${api_dir}/authenticate.sh" "${environment}"

# Function names are computed the same way each component's own Terraform names them.
export ENVIRONMENT="${environment}"

mvn -f verify/pom.xml clean verify
