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

# The suite reads data back through GraphQL as the API's machine-to-machine client - deliberately a
# different identity from demo-data's own, since it is the harness rather than the thing under
# test. Looked up in SSM Parameter Store, where mootmaker-api's deploy publishes it
# (mootmaker-api#94), and passed to the test JVM only.
ssm_value() {
  local value
  if ! value="$(aws ssm get-parameter --name "/mootmaker/${environment}/api/$1" --with-decryption --query Parameter.Value --output text 2>&1)"; then
    echo "Could not read /mootmaker/${environment}/api/$1 - has mootmaker-api been deployed to '${environment}'?" >&2
    echo "${value}" >&2
    exit 1
  fi
  printf '%s' "${value}"
}
GRAPHQL_API_URL="$(ssm_value graphql-url)"
COGNITO_TOKEN_URL="$(ssm_value m2m-client/token-url)"
COGNITO_TEST_CLIENT_ID="$(ssm_value m2m-client/client-id)"
COGNITO_TEST_CLIENT_SECRET="$(ssm_value m2m-client/client-secret)"
COGNITO_TEST_SCOPE="$(ssm_value m2m-client/scope)"
export GRAPHQL_API_URL COGNITO_TOKEN_URL COGNITO_TEST_CLIENT_ID COGNITO_TEST_CLIENT_SECRET COGNITO_TEST_SCOPE

# Function names are computed the same way each component's own Terraform names them.
export ENVIRONMENT="${environment}"

mvn -f verify/pom.xml clean verify
