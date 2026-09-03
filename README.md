# mootmaker-demo-data

Demo data for the [mootmaker](https://github.com/geoffweatherall/mootmaker) project: one Lambda
that keeps an environment populated with realistic people, rooms and meetings.

This is one of the project's **three deployable components**, alongside
[mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) and
[mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp). Demo is a core part of
MootMaker rather than test scaffolding — the public demo at www.mootmaker.com is what this fills —
so it is always deployed to `production`, and optional for ephemeral environments.

## This tool never deletes anything

There is no reset path here, and no way to reach one. A run only ever *adds*: it creates the
people, rooms and meetings that are missing, and leaves everything that already exists alone.

That is a deliberate change. Its predecessor, `sample-data-generator`, reset the database as the
first step of every run — behind a script called `run.sh`. Clearing an environment is now a
separate, deliberate step: invoke `mootmaker-api`'s `database-reset` yourself first, then invoke
this. Two commands, and neither is a surprise.

## What a run does

Three independent concerns, each of which does nothing once its target is already met:

| Concern | Target | What makes it repeatable |
|---|---|---|
| People | `TARGET_PEOPLE` (default 40) | Creates the shortfall only |
| Rooms | `TARGET_ROOMS` (default 10) | Creates the shortfall only, never reusing an existing room's name |
| Meetings | every weekday from `DAYS_IN_PAST` (default 7) behind today to `WEEKS_AHEAD` (default 6) ahead | Skips any day that already has a meeting |

Seeding a fresh environment and topping up `production` are therefore the **same operation** — on a
new environment every day in the window is empty, so the run fills all of them; on a populated one
it fills whichever single day has just entered the window. There is no mode to choose and none to
get wrong.

The people target counts **all** people, not just generated ones: `Person` exposes no Cognito
linkage through the GraphQL API, so this tool genuinely cannot tell a demo person from a real
signed-up one. In an environment where real sign-ups have passed the target, no demo people are
created — there are already enough people to book meetings with.

## Running it

```bash
# Everything (what the schedule does)
aws lambda invoke --function-name <environment>-mootmaker-demo-data \
  --cli-read-timeout 900 --payload '{}' /dev/stdout

# Just the meetings, leaving people and rooms alone
aws lambda invoke --function-name <environment>-mootmaker-demo-data \
  --cli-read-timeout 900 --payload '{"people": false, "rooms": false}' /dev/stdout
```

**`--cli-read-timeout 900` is not optional.** The function's own timeout is the AWS maximum of 900
seconds, and the AWS CLI defaults to a 60-second read timeout — without this, a legitimately long
run (a full seed of a fresh environment) is reported to you as a failure while the Lambda carries
on and completes regardless.

Every concern defaults to enabled, so the scheduled invocation's empty payload runs all three.
Magnitudes — how many people and rooms, how wide the window — are Terraform variables rather than
payload fields, so a mistyped invocation can switch a concern off but can never ask for 4,000
people.

Runs are not serialised. The function would reserve a concurrency of 1 to make overlap
structurally impossible, but this AWS account's total Lambda concurrency quota is 10 and AWS refuses
any reservation leaving fewer than 10 unreserved — so no value is settable. This is an accepted
risk: overlap needs a manual invoke to land inside the few seconds the daily scheduled run is
active, and the worst case is a few extra rooms or people in a demo environment, after which the
next run is a no-op again because every concern is defined by its target rather than by what it last
did.

### To clear and repopulate

```bash
aws lambda invoke --function-name <environment>-mootmaker-database-reset \
  --cli-read-timeout 900 --payload '{}' /dev/stdout    # mootmaker-api's tool, not this one
aws lambda invoke --function-name <environment>-mootmaker-demo-data \
  --cli-read-timeout 900 --payload '{}' /dev/stdout
```

## Build, test, deploy

```bash
mvn -f impl/pom.xml clean package     # unit tests
./deploy.sh <environment>             # build the jar, create/update the Lambda
./deploy.sh <environment> --skip-build  # deploy the existing impl/target/demo-data.jar unchanged
                                      # (used by the release pipeline to promote one build)
./verify.sh <environment>             # acceptance tests (destructive - never production)
./undeploy.sh <environment>           # remove it (prompts for confirmation)
./undeploy.sh <environment> --yes     # no prompt, for automation; refuses production and test
```

`deploy.sh` needs **nothing** from `mootmaker-api`'s Terraform state — only the environment name.
Everything else (GraphQL URL, Cognito token endpoint, client id/secret, scopes) is read at runtime
from SSM Parameter Store, at paths derived from the environment name.

**`mootmaker-api` must be deployed to an environment before this is**, since its Terraform is what
creates those parameters. Deploy this first and the Lambda comes up fine, then fails on its first
invocation with a missing-parameter error.

## Authentication

Machine-to-machine, via the OAuth2 `client_credentials` flow: this component has **its own** Cognito
app client (`<environment>-mootmaker-demo-data`), defined in `mootmaker-api`'s `cognito.tf`, whose
id and secret it reads from SSM at runtime. It never acts as a user — it cannot, since the webapp's
Cognito client permits only SRP auth — and it holds no credential in an environment variable or in
this project's Terraform state.

It used to borrow the *acceptance tests'* client, with the secret passed in as a plaintext Lambda
environment variable. That shared one credential between two unrelated consumers and put it
somewhere any holder of `lambda:GetFunctionConfiguration` could read.

The token is fetched **once per run**, never per request: Cognito bills M2M token requests with no
free tier at all, so a token per GraphQL call would cost hundreds of times more on a full seed.

## Scheduling

An EventBridge rule invokes the Lambda daily at 06:00 UTC. It is **enabled only in `production`** by
default — an ephemeral environment that outlives its work should not also sit there invoking a
Lambda every day against an API that may be half-torn-down. Set `schedule_enabled` explicitly to
exercise it elsewhere.

## Everything it writes goes through the API

This component holds no DynamoDB code at all. It creates data by calling `createPerson`,
`createRoom` and `createMeeting` exactly as the webapp would, which makes generated demo data proof
that the API's own validation accepts it. Writing directly to DynamoDB would be faster and would let
it construct states the API rejects — which is exactly why it doesn't.

## Testing

See [testing-strategy.md](testing-strategy.md).
