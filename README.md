# mootmaker-demo-data

Demo-data tools for the [mootmaker](https://github.com/geoffweatherall/mootmaker) project. Renamed
from `mootmaker-tools` on 2026-08-29, when `database-reset` and `database-repair` split out into
[mootmaker-admin-tools](https://github.com/geoffweatherall/mootmaker-admin-tools) by blast radius:
this repo now holds only tooling that ships as part of the production demo, not tooling that can
destroy data.

Like [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) and
[mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp), each tool here is deployed
— as its own AWS Lambda function, one per target environment — to help set up or maintain an
already-deployed environment (see
[`../mootmaker/docs/process/environments.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/environments.md)
for how environments work). sample-data-generator is invoked on demand; sample-data-topup also runs
itself automatically on a schedule (see its own README).

This checkout expects `mootmaker-api` to be a sibling directory — both deploying a tool and invoking
it read the target environment's Terraform outputs (via `mootmaker-api`'s `authenticate.sh`), the
same way `mootmaker-webapp` does.

Each tool follows the same layout and scripts as `mootmaker-api`: an `impl/` Maven project with the
Lambda handler code, a `deploy/terraform/` directory with its Terraform, and
`deploy.sh`/`undeploy.sh`/`run.sh` scripts that all take the target environment as their first
argument. `deploy.sh` builds the jar and creates/updates the Lambda; `run.sh` invokes the
already-deployed Lambda and prints its result; `undeploy.sh` deletes it. See each tool's own README
for details and any extra `run.sh` arguments.

## Tools

| Tool | Purpose |
|---|---|
| [sample-data-generator](sample-data-generator/README.md) | Resets an environment (by invoking `database-reset` — see below) and populates it with realistic sample people, rooms, and meetings |
| [sample-data-topup](sample-data-topup/README.md) | Runs weekly (EventBridge schedule, no manual trigger needed) and fills in any weekday in the next 6 weeks that has no meetings at all — unlike sample-data-generator, never resets or deletes anything, so it's safe to leave running unattended |

## The dependency that crosses a repository boundary

sample-data-generator invokes `database-reset` directly (Lambda-to-Lambda, via its own IAM role —
see [sample-data-generator's README](sample-data-generator/README.md#how-it-is-deployed)) as the
first step of every run. That tool now lives in
[../mootmaker-admin-tools](https://github.com/geoffweatherall/mootmaker-admin-tools) — the coupling
is unchanged by the split (it was always a deterministic function-name invocation, never a
cross-project Terraform state read), but it now crosses a repository boundary rather than staying
within one.

**`database-reset` (in `mootmaker-admin-tools`) must be deployed to an environment before
sample-data-generator is deployed or run against it.** `mootmaker-api`'s own acceptance tests have
the same dependency (see the
[mootmaker-api README](https://github.com/geoffweatherall/mootmaker-api#authentication-in-end-to-end-tests)).

sample-data-topup works against whatever rooms/people already exist, so it's best deployed after an
environment has been seeded at least once by sample-data-generator (see
[sample-data-topup's README](sample-data-topup/README.md#prerequisites)).

**[deploy-all.sh](deploy-all.sh)/[undeploy-all.sh](undeploy-all.sh)**, at the root of this project,
deploy or undeploy both tools against a single environment in one command
(`./deploy-all.sh <environment>`) — sample-data-generator then sample-data-topup for deploy; the
reverse for undeploy. Neither deploys `database-reset` itself — that is a separate repository now,
deployed via its own `deploy-all.sh` in `mootmaker-admin-tools`, and must run first. Each script is
just a loop over the individual tools' own `deploy.sh`/`undeploy.sh` (same environment argument
passed straight through to each), so they carry the same behaviour and safety properties as running
each tool's script by hand: `deploy-all.sh` runs real `terraform apply -auto-approve` calls, and
`undeploy-all.sh` still prompts for interactive confirmation once per tool, since neither individual
`undeploy.sh` script passes `-auto-approve`.
