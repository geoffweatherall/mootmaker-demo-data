bucket       = "remote-state-431071856068"
region       = "us-east-1"
use_lockfile = true

# `key` is deliberately not set here - it's supplied per-environment at init
# time (see deploy.sh/undeploy.sh), so state lives at
# <environment>/mootmaker-tools-database-reset/terraform.tfstate in this bucket - the same
# bucket mootmaker-api/mootmaker-webapp use, per mootmaker-bootstrap-terraform's README.
