# sample-data-generator

Resets a deployed [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) environment and populates it with realistic-looking sample data, so the environment (production included — this project's production is a demo, not a real user-facing system) has something worth looking at without manually clicking through the webapp.

Deployed as its own AWS Lambda function, one per target environment, and invoked on demand via `./run.sh <environment>` rather than run continuously - see [How it is deployed](#how-it-is-deployed) below.

## What it does

Invoking the tool, in order:

1. Invokes [mootmaker-api's database-reset](https://github.com/geoffweatherall/mootmaker-api#reset-and-real-user-accounts), which deletes all rooms and meetings. What happens to People depends on the target environment: outside `production`, reset also wipes the Cognito pool down to the two Terraform-managed reserved accounts (demo, e2e) and deletes every Person not linked to one of them — so in practice, the only Person likely to survive into step 2 below is the demo user's. In `production`, reset keeps its original, broader rule instead: every Person linked to *any* real Cognito account survives (real signed-up visitors included), since production's Cognito wipe is refused outright. This is a direct Lambda-to-Lambda invocation (AWS SDK, IAM auth), not a GraphQL call - see [How it is deployed](#how-it-is-deployed).
2. Looks up whoever is left after that reset — i.e. real signed-up users — and includes them alongside the newly-created people below when scheduling meetings, so real accounts show up with a realistic-looking calendar too, not just sample data.
3. Creates **40 people**, with realistic full names (via [datafaker](https://www.datafaker.net/)) — enough that the room-filling meetings below can actually be staffed alongside everything else. This count doesn't change based on how many real users already exist; they're additional people to book, not a replacement for any of the 40.
4. Creates **10 rooms**, each with a meaningful name (e.g. `Everest`, `Boardroom`, `The Hub`) and a random capacity between 4 and 20.
5. Creates **meetings across every business day from a week ago to seven weeks from now** (Monday-Friday only): each room gets 0-2 sequential meetings per day (so the exact total varies run to run), every meeting within business hours (08:00-17:00), with a realistic subject (e.g. `Sprint Planning`, `Client Onboarding Call`), an organiser plus **at least one** attendee (drawn from both the newly-created people and any existing real users), and a randomly chosen duration (15/30/45/60/90/120 minutes) on the API's required 15-minute boundary.

   Scheduling rules, enforced by `MeetingScheduler`:
   - **A room is never double-booked**: each room's meetings for a day are placed back-to-back or with gaps, never overlapping.
   - **A person (organiser or attendee) is never in two meetings at once**, tracked across *every* room and day - so nobody ends up double-booked just because they were picked for two different rooms' meetings at the same time.
   - **Meetings in different rooms may legitimately overlap in time** - that's realistic (two unrelated meetings happening at once in different rooms) and is only prevented between meetings that share a room or a person.
   - **Each room's first meeting of the day starts at a random point in the day**, not always 08:00, so meetings don't all bunch up at the start of business hours.
   - **At least half of a person's meetings are followed by a real gap** before their next one, rather than being back-to-back, so a person's calendar looks like a real one rather than a packed schedule.
   - **At least half of all meetings use at least half the room's capacity** (a mix of small catch-ups and larger, room-filling sessions), with every meeting still respecting the capacity limit.

Creating people, rooms, and meetings each run up to **8 requests concurrently** rather than one at a time - the full schedule is worked out up front with no overlapping room or person times anywhere in it (see the scheduling rules above), so the order in which the ~600 `createMeeting` calls actually reach the server doesn't matter, and they don't need to wait on one another. 8 is a deliberately modest cap - enough to meaningfully cut down the several hundred network round trips this involves, without throwing a burst of concurrent traffic at what's usually a small demo deployment. It's also what keeps a full run comfortably inside a Lambda invocation's 15-minute hard ceiling as sample data volume grows, rather than the function's configured timeout doing that work.

Expect output like:

```
Resetting environment...
Found 2 existing person(s) with a Cognito account; including them when scheduling meetings...
  Jane Doe
  John Smith
Creating 40 people...
  Ada Lovelace
  ...
Creating 10 rooms...
  Everest (capacity 12)
  ...
Creating 604 meetings from 7 days ago to 49 days ahead...
  Sprint Planning - 2026-07-16T09:15:00 to 2026-07-16T10:15:00
  ...

Done: 40 new people (+2 existing Cognito-linked person(s)), 10 rooms, 604 meetings created.
```

(The "Found ... existing person(s)" line is only printed when there are any — a freshly bootstrapped environment with no real sign-ups yet won't show it.)

## How it is deployed

```
                          ┌─HTTPS/GraphQL──▶ AWS AppSync ──▶ mootmaker-api
./run.sh <environment> ──▶ Lambda (Java)
                          └─AWS SDK (IAM)──▶ database-reset Lambda ──▶ DynamoDB
```

- The Lambda authenticates to the target `mootmaker-api` environment the same way its own acceptance tests do: the OAuth2 client_credentials flow, using the `mootmaker-acceptance-tests` app client's id/secret (see the [API README's Authentication section](https://github.com/geoffweatherall/mootmaker-api#authentication)). `deploy.sh` reads those, along with the GraphQL endpoint, from the target environment's Terraform outputs and sets them as this function's environment variables (`GRAPHQL_API_URL`, `COGNITO_TOKEN_URL`, `COGNITO_TEST_CLIENT_ID`, `COGNITO_TEST_CLIENT_SECRET`, `COGNITO_TEST_SCOPE`) - see [GraphQlClient.fromEnvironment()](impl/src/main/java/com/mootmaker/tools/sampledata/GraphQlClient.java).
- The reset step (see [What it does](#what-it-does)) invokes [database-reset](https://github.com/geoffweatherall/mootmaker-api#reset-and-real-user-accounts)'s Lambda directly via the AWS SDK ([DatabaseResetInvoker](impl/src/main/java/com/mootmaker/tools/sampledata/DatabaseResetInvoker.java)), authenticated as this function's own IAM role rather than a GraphQL call. Its IAM role is granted `lambda:InvokeFunction` on exactly that one function, whose name/ARN this project's own Terraform computes deterministically from the environment name (see [deploy/terraform/locals.tf](deploy/terraform/locals.tf)) - the same way `database-reset`'s own Terraform does, rather than depending on `mootmaker-api`'s Terraform state directly. **This means database-reset must already be deployed for an environment before this tool is deployed or run against it** - see [mootmaker-api's README](https://github.com/geoffweatherall/mootmaker-api#reset-and-real-user-accounts). (This dependency briefly crossed a third repository, `mootmaker-admin-tools`, between 2026-08-29 and 2026-09-02 — see [mootmaker/designs/admin-tools-into-api.md](https://github.com/geoffweatherall/mootmaker/blob/main/designs/admin-tools-into-api.md) — before `database-reset` moved into `mootmaker-api` itself. `mootmaker-admin-tools` no longer exists.)
- Beyond that, the function needs no other AWS-service permissions: everything else it does is an outbound HTTPS call (to the Cognito token endpoint and the AppSync GraphQL endpoint), never touching DynamoDB or any other AWS service directly.
- Runtime is Java 25, 512 MB, 300 s timeout - generous headroom over how long a full run actually takes (well under a minute in practice, at up to 8 concurrent GraphQL calls), given a Lambda invocation is hard-capped at 15 minutes regardless of this setting. (`database-reset` itself now runs at the 900 s Lambda maximum rather than 300 s - see its own repo's docs - but that's a property of the invoked function, not this one.)
- `GenerateSampleDataHandler` is the Lambda entry point; the input payload is unused (there's nothing to configure per invocation), and its response payload is a small JSON summary (counts of people/rooms/meetings created). The full step-by-step log (the same output shown above) goes to CloudWatch Logs, under `/aws/lambda/<environment>-mootmaker-sample-data-generator`.

## Directory structure

| Path | Contents |
|---|---|
| [impl/](impl/) | Maven project with the Lambda handler (`GenerateSampleDataHandler`), the generation/scheduling logic (`SampleDataGenerator`, `MeetingScheduler`), the GraphQL client, the `database-reset` invoker (`DatabaseResetInvoker`), and unit tests. Builds the shaded jar deployed to Lambda. |
| [deploy/terraform/](deploy/terraform/) | Terraform for the Lambda function and its execution role. State is stored remotely in S3, one state file per environment (the same bucket `mootmaker-api`/`mootmaker-webapp` use - see [backend.hcl](deploy/terraform/backend.hcl)). |

### Bash scripts

All scripts live in the project root and are run from there:

| Script | What it does | How to run |
|---|---|---|
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), reads the target environment's GraphQL/Cognito settings from `mootmaker-api`'s Terraform outputs, then `terraform init` + `terraform apply -auto-approve` to create/update the Lambda function, its execution role, and its `lambda:InvokeFunction` grant on `database-reset` **for the given environment**. Creates real AWS resources - run deliberately. `database-reset` must already be deployed for this environment (see [How it is deployed](#how-it-is-deployed)). | `./deploy.sh <environment>` |
| [run.sh](run.sh) | Invokes the already-deployed Lambda for the given environment via `aws lambda invoke`, prints the tail of its CloudWatch log output and the JSON summary it returns. This is what actually resets and repopulates the target environment. | `./run.sh <environment>` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` - deletes the Lambda function and its execution role for the given environment. Does not touch the target `mootmaker-api` environment. Prompts for confirmation. | `./undeploy.sh <environment>` |

## Prerequisites

- Java 25 and Maven, Terraform ≥ 1.10, and the AWS CLI (same as [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api)), plus `jq` (used by `run.sh` to parse the Lambda invoke response).
- A `mootmaker-api` checkout as a sibling of `mootmaker-demo-data` (i.e. `mootmaker-demo-data` and `mootmaker-api` share a parent directory), deployed to the environment you want to target.
- `database-reset` (in [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api#reset-and-real-user-accounts)) already deployed for that same environment (this tool invokes it directly - see [How it is deployed](#how-it-is-deployed)).

## Usage

```bash
# database-reset must already be deployed to this environment - it's part of mootmaker-api's own deploy.sh
../../mootmaker-api/deploy.sh bob

# Deploy (build + terraform apply) to an environment, e.g. an ephemeral name or "production"
./deploy.sh bob

# Invoke it - resets and repopulates that environment with sample data
./run.sh bob

# Tear the Lambda down when you're done with it (the target mootmaker-api environment is untouched)
./undeploy.sh bob
```

For example, `./deploy.sh test && ./run.sh test`, or against `production`. Safe to run against `production` too — this project's production deployment is itself a demo environment, not a real user-facing system, so keeping it populated with realistic sample data is the point.

## Why a Maven project instead of a script

The generator needs to call `createMeeting`/`createRoom`/`createPerson` with values that satisfy the API's validation rules (15-minute time boundaries, room capacity, no overlapping meetings for the same room) — see the [API README's Validation section](https://github.com/geoffweatherall/mootmaker-api#validation). That scheduling logic (`MeetingScheduler`) is plain, dependency-free Java with its own unit tests (`mvn test`), independent of the GraphQL calls it feeds into (`SampleDataGenerator`/`GraphQlClient`). The bounded-concurrency helper the generator uses to run those calls in parallel (`SampleDataGenerator.runInParallel`) has its own tests too (`SampleDataGeneratorConcurrencyTest`), covering that every item still gets processed exactly once, that work is actually spread across more than one thread, and that a failure partway through still surfaces after everything else has finished. `DatabaseResetInvoker`'s response handling (the successful case, and surfacing a `FunctionError` from the invoked Lambda as a clear exception) is covered by `DatabaseResetInvokerTest` against a fake in-memory `LambdaClient`, independent of a real AWS call.
