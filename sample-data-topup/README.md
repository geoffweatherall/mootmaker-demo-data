# sample-data-topup

Keeps a deployed [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) environment's calendar looking populated as time passes, without ever resetting or deleting anything.

Runs automatically once a week (via an EventBridge schedule - see [How it is deployed](#how-it-is-deployed)), or on demand via `./run.sh <environment>`.

## What it does

Looks at every weekday (Monday-Friday - it's normal and expected for a Saturday or Sunday to have none) in the **next 6 weeks**, and for any weekday that currently has **no meetings at all**, adds a realistic day's worth of sample meetings to it, using the exact same room-booking rules as [sample-data-generator](../sample-data-generator/README.md)'s `MeetingScheduler` (business hours, no double-booking a room or person, a mix of small catch-ups and room-filling sessions, gaps between a person's meetings, etc. - see that tool's README for the full rule list).

A weekday with **any** existing meeting - even just one, real or sample - is left completely alone; this tool only ever fills in days that are entirely empty.

Unlike sample-data-generator, this tool:

- **Never resets or deletes anything.** It reuses whatever rooms and people already exist (real signed-up users and any earlier sample data alike) and only ever adds meetings - it never touches an existing room, person, or meeting.
- **Doesn't create new rooms or people.** If an environment has no rooms, or fewer than 2 people to book as organiser/attendee, there's nothing it can do - it logs why and exits cleanly rather than erroring (run [sample-data-generator](../sample-data-generator/README.md) first to seed some).

That combination is what makes it safe to run unattended on a schedule, including against `production` (this project's production deployment is itself a demo, not a real user-facing system, but even so - a real signed-up user's own meetings are never at risk here, unlike a full `sample-data-generator` run which resets everything first).

**Why 6 weeks, why weekly:** sample-data-generator itself populates 7 weeks ahead on top of resetting, so a fresh environment starts out fully covered. As real time passes, the far edge of that populated window keeps receding relative to "today" - this tool's job is to notice the newly-uncovered days at the end of a shrinking window and fill them back in, so the calendar always looks populated about a month and a half out without ever needing a disruptive full reset. Running weekly comfortably keeps ahead of that 6-week window one week at a time; the exact day/time doesn't matter (see `var.schedule_expression` in [deploy/terraform/variables.tf](deploy/terraform/variables.tf) if you want to change it).

Expect output like:

```
Checking for empty weekdays between 2026-07-29 and 2026-09-08...
Found 5 empty weekday(s): [2026-09-04, 2026-09-07, 2026-09-08]
Creating 34 meeting(s)...
  Sprint Planning - 2026-09-04T09:15:00 to 2026-09-04T10:15:00
  ...

Done: 5 weekday(s) topped up, 34 meeting(s) created.
```

Or, most weeks, once an environment is already fully topped up:

```
Checking for empty weekdays between 2026-07-29 and 2026-09-08...
Every weekday in the window already has at least one meeting - nothing to do.
```

## How it is deployed

```
EventBridge (weekly) ──┐
                        ├──▶ Lambda (Java) ──HTTPS/GraphQL──▶ AWS AppSync ──▶ mootmaker-api
     ./run.sh ──────────┘
```

- **Triggering is free.** An EventBridge rule with a schedule expression (`cron(0 6 ? * MON *)` by default - every Monday at 06:00 UTC) invokes the Lambda directly; scheduled rules targeting an AWS service aren't billed at all, and the Lambda invocations themselves (once a week) fall entirely within Lambda's always-free tier. See [deploy/terraform/schedule.tf](deploy/terraform/schedule.tf). Set `-var="schedule_enabled=false"` (or edit the default in [variables.tf](deploy/terraform/variables.tf)) to pause automatic runs without destroying the schedule.
- The Lambda authenticates to the target `mootmaker-api` environment the same way sample-data-generator and mootmaker-api's own acceptance tests do: the OAuth2 client_credentials flow, using the `mootmaker-acceptance-tests` app client's id/secret (see the [API README's Authentication section](https://github.com/geoffweatherall/mootmaker-api#authentication)). `deploy.sh` reads those, along with the GraphQL endpoint, from the target environment's Terraform outputs and sets them as this function's environment variables - see [GraphQlClient.fromEnvironment()](impl/src/main/java/com/mootmaker/tools/sampledatatopup/GraphQlClient.java).
- The function needs no AWS-service permissions beyond basic execution/logging: it only ever makes outbound HTTPS calls (to the Cognito token endpoint and the AppSync GraphQL endpoint) - unlike sample-data-generator, it never invokes another Lambda either, since it never resets anything.
- Runtime is Java 25, 512 MB, 300 s timeout - sized for the worst case (a freshly-seeded environment where every weekday in the window is still empty, generating a similar volume to a full sample-data-generator run), even though a steady-state weekly run only tops up a handful of newly-uncovered days.
- `TopUpSampleDataHandler` is the Lambda entry point; the input payload is unused, and its response payload is a small JSON summary (weekdays topped up, meetings created). The full step-by-step log (the same output shown above) goes to CloudWatch Logs, under `/aws/lambda/<environment>-mootmaker-sample-data-topup`.

## Directory structure

| Path | Contents |
|---|---|
| [impl/](impl/) | Maven project with the Lambda handler (`TopUpSampleDataHandler`), the empty-day detection and orchestration logic (`SampleDataTopUp`), the scheduling logic (`MeetingScheduler`), the GraphQL client, and unit tests. Builds the shaded jar deployed to Lambda. |
| [deploy/terraform/](deploy/terraform/) | Terraform for the Lambda function, its execution role, and the weekly EventBridge schedule. State is stored remotely in S3, one state file per environment (the same bucket `mootmaker-api`/`mootmaker-webapp` use - see [backend.hcl](deploy/terraform/backend.hcl)). |

### Bash scripts

All scripts live in the project root and are run from there:

| Script | What it does | How to run |
|---|---|---|
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), reads the target environment's GraphQL/Cognito settings from `mootmaker-api`'s Terraform outputs, then `terraform init` + `terraform apply -auto-approve` to create/update the Lambda function and its weekly EventBridge schedule **for the given environment**. Creates real AWS resources - run deliberately. | `./deploy.sh <environment>` |
| [run.sh](run.sh) | Invokes the already-deployed Lambda for the given environment via `aws lambda invoke` (the same thing the weekly schedule does automatically), prints the tail of its CloudWatch log output and the JSON summary it returns. | `./run.sh <environment>` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` - deletes the Lambda function, its execution role, and the EventBridge schedule for the given environment. Does not touch the target `mootmaker-api` environment or any data. Prompts for confirmation. | `./undeploy.sh <environment>` |

## Prerequisites

- Java 25 and Maven, Terraform ≥ 1.10, and the AWS CLI (same as [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api)), plus `jq` (used by `run.sh` to parse the Lambda invoke response).
- A `mootmaker-api` checkout as a sibling of `mootmaker-tools` (i.e. `mootmaker-tools` and `mootmaker-api` share a parent directory), deployed to the environment you want to target.
- At least one room and two people already in the target environment for there to be anything to top up with (see [What it does](#what-it-does)) - run [sample-data-generator](../sample-data-generator/README.md) first if starting from empty.

## Usage

```bash
# Deploy (build + terraform apply) to an environment, e.g. "test" or your own name -
# this also creates the weekly EventBridge schedule, so no further action is needed
# for it to keep running automatically.
./deploy.sh test

# Invoke it manually any time, e.g. to top up immediately rather than waiting for the schedule
./run.sh test

# Tear the Lambda and its schedule down when you're done with it (no data is touched)
./undeploy.sh test
```

Safe to run against `production` too, and safe to leave running unattended - see [What it does](#what-it-does) for why.

## Why a Maven project instead of a script

Same reasoning as [sample-data-generator](../sample-data-generator/README.md): the meeting-scheduling logic needs to satisfy the API's validation rules and is worth covering with unit tests independent of the GraphQL calls it feeds into. `MeetingSchedulerTest` covers the same scheduling invariants sample-data-generator's does (never double-booking a room or person, business hours, capacity, the gap/large-meeting ratios, etc.) against this copy's slightly different entry point (an explicit list of target dates rather than a day-offset range - see that class's doc comment for why). `SampleDataTopUpTest` covers the pure date-range logic behind picking which weekdays are candidates (`weekdaysBetween`), and `SampleDataTopUpConcurrencyTest` covers the bounded-concurrency helper used to parallelise `createMeeting` calls, identical in shape to the equivalent tests in the other `mootmaker-tools` projects.
