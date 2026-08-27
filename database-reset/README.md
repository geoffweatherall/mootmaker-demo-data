# database-reset

Deletes all stored rooms and meetings, and every person **except** those linked to a real Cognito account, from a deployed [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) environment (production included — this project's production is a demo, not a real user-facing system).

This used to be `Mutation.reset` in the GraphQL API, callable by any signed-in user. It has moved here so that wiping data needs an explicit, IAM-authenticated Lambda invocation instead - see [Why this moved out of the GraphQL API](#why-this-moved-out-of-the-graphql-api).

Deployed as its own AWS Lambda function, one per target environment, and invoked on demand via `./run.sh <environment>` rather than run continuously - see [How it is deployed](#how-it-is-deployed) below.

## What it does

- Deletes every stored room.
- Deletes every stored meeting, and its meeting-participants rows (see the [API README's meeting-participants section](https://github.com/geoffweatherall/mootmaker-api#the-meeting-participants-table)) - meeting-participants is a derived index, never a source of truth, so it's always safe to delete alongside the meeting that owns it.
- Deletes every person **without** a linked Cognito account. A person's `cognitoSub` is their only link back to their real account (nothing recreates it after the fact), so a person *with* one - a real signed-up user, the demo user, or the e2e test user - is always preserved. Guests added directly (no login) have no such link and are always cleared. This is what keeps `run.sh` safe to point at `production`: it never touches a real account, and this project's production deployment is itself a public demo, not a real user-facing system.
  - **Caveat:** this check only looks at whether `cognitoSub` is *present* on the person, not whether that Cognito user still actually exists. If a Cognito account is ever deleted and recreated (e.g. the e2e test user, if its Terraform resource is ever replaced), the old Cognito `sub` no longer matches anything, but the Person record referencing it still has `cognitoSub` set - so reset keeps preserving it forever as a stray, orphaned record. Reset has no way to detect or clean this up; a stray Person like this has to be deleted directly via DynamoDB. See also [database-repair](../database-repair/README.md#known-gap-stray-person-records), which doesn't have a repair for this case either.

These three deletions touch entirely different tables and don't depend on each other, so they run **concurrently** (see [How it is deployed](#how-it-is-deployed)) rather than one after another.

Expect output like:

```
Deleted 10 room(s), 38 unlinked person(s), 604 meeting(s) (and their participant rows).
```

## Why this moved out of the GraphQL API

`Mutation.reset` used to be reachable by any signed-in user of the product - the [mootmaker business functionality doc](https://github.com/geoffweatherall/mootmaker/blob/main/functionality/business-functionality.md) called this out as a known gap ("not currently restricted to administrators or disabled in customer-facing settings"). Moving it here closes that gap: it's no longer part of the API surface at all, so no webapp user, however they're signed in, can reach it. The only way to invoke it now is `lambda:InvokeFunction` on this specific Lambda - an AWS IAM permission granted explicitly (see [How it is deployed](#how-it-is-deployed)), not something any product user has.

## Who calls this

- **A developer**, directly, via `./run.sh <environment>` - to clear out a personal sandbox or a shared non-production environment.
- **[sample-data-generator](../sample-data-generator/README.md)**, as the first step of every run - it invokes this Lambda (via the AWS SDK, not GraphQL) before creating its sample people/rooms/meetings. That means **database-reset must be deployed for an environment before sample-data-generator can run against it** - `deploy.sh` doesn't enforce this ordering itself, since it doesn't know sample-data-generator will be used, but sample-data-generator's own `run.sh` will fail with an AWS "function not found" error if this Lambda isn't deployed yet.
- **mootmaker-api's own acceptance tests** ([verify/](https://github.com/geoffweatherall/mootmaker-api/tree/main/verify)), the same way sample-data-generator does, to reset the database to a known state before each test - see the API README's testing section. This means database-reset must also be deployed before running `mootmaker-api/verify.sh` against a given environment.

## How it is deployed

```
./run.sh <environment> ──aws lambda invoke──▶ Lambda (Java) ──AWS SDK──▶ DynamoDB (target environment)
```

- `DatabaseResetHandler` is the Lambda entry point; the input payload is unused (there's nothing to configure per invocation). The three deletion passes (`DatabaseReset.deleteAllItems` for rooms, `deleteUnlinkedPeople`, `deleteAllMeetingsAndParticipants`) run concurrently on their own threads, and each pass's own per-item `DeleteItem` calls are themselves spread across a bounded pool of up to 8 concurrent requests (`DatabaseResetHandler.runInParallel`) - together, what keeps a run comfortably inside a Lambda invocation's 15-minute hard ceiling as stored data volume grows, rather than the function's configured timeout doing that work.
- The function reads `ROOMS_TABLE_NAME`, `PEOPLE_TABLE_NAME`, `MEETINGS_TABLE_NAME`, and `MEETING_PARTICIPANTS_TABLE_NAME` from its own environment variables (set by `deploy.sh` from the target environment's Terraform outputs), and its region from the `AWS_REGION` variable Lambda sets automatically.
- Its IAM role is granted only `dynamodb:Scan` and `dynamodb:DeleteItem`, scoped to exactly those four tables (see [deploy/terraform/iam.tf](deploy/terraform/iam.tf)) - narrower than what the shared Lambda execution role inside mootmaker-api used to need for this, since that role also had to cover every other resolver's `PutItem`/`Query`/etc.
- Runtime is Java 25, 512 MB, 300 s timeout.
- The response payload is a small JSON summary (counts of rooms/people/meetings deleted); the full log goes to CloudWatch Logs, under `/aws/lambda/<environment>-mootmaker-database-reset`.
- Nothing grants other principals permission to invoke this function by default - `sample-data-generator`'s own Terraform grants its Lambda's execution role `lambda:InvokeFunction` on this one specifically (see [sample-data-generator/deploy/terraform/iam.tf](../sample-data-generator/deploy/terraform/iam.tf)), and a developer running `run.sh` needs that same permission on their own AWS credentials.

## Directory structure

| Path | Contents |
|---|---|
| [impl/](impl/) | Maven project with the Lambda handler (`DatabaseResetHandler`), the deletion logic (`DatabaseReset`), the duplicated meeting/participant model records, and unit tests. Builds the shaded jar deployed to Lambda. |
| [deploy/terraform/](deploy/terraform/) | Terraform for the Lambda function, its execution role, and the IAM policy above. State is stored remotely in S3, one state file per environment (the same bucket `mootmaker-api`/`mootmaker-webapp` use - see [backend.hcl](deploy/terraform/backend.hcl)). |

### Bash scripts

All scripts live in the project root and are run from there:

| Script | What it does | How to run |
|---|---|---|
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), reads the target environment's DynamoDB table names from `mootmaker-api`'s Terraform outputs, then `terraform init` + `terraform apply -auto-approve` to create/update the Lambda function and its IAM role/policy **for the given environment**. Creates real AWS resources - run deliberately. | `./deploy.sh <environment>` |
| [run.sh](run.sh) | Invokes the already-deployed Lambda for the given environment via `aws lambda invoke`, prints the tail of its CloudWatch log output and the JSON summary it returns. This is what actually deletes data. | `./run.sh <environment>` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` - deletes the Lambda function and its IAM role/policy for the given environment. Does not touch the target `mootmaker-api` environment (i.e. does not delete any data). Prompts for confirmation. | `./undeploy.sh <environment>` |

## Prerequisites

- Java 25 and Maven, Terraform ≥ 1.10, and the AWS CLI (same as [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api)), plus `jq` (used by `run.sh` to parse the Lambda invoke response).
- A `mootmaker-api` checkout as a sibling of `mootmaker-tools` (i.e. `mootmaker-tools` and `mootmaker-api` share a parent directory), deployed to the environment you want to target.
- AWS credentials able to run `deploy.sh`/`undeploy.sh` (i.e. to manage the Lambda function and IAM role/policy themselves) - the same credentials used to deploy `mootmaker-api`/`mootmaker-webapp` work. `run.sh` only needs `lambda:InvokeFunction` on the deployed function.

## Usage

```bash
# Deploy (build + terraform apply) to an environment, e.g. "test" or your own name
./deploy.sh test

# Delete rooms/meetings/unlinked-people in that environment
./run.sh test

# Tear the Lambda down when you're done with it (the target mootmaker-api environment's data is untouched)
./undeploy.sh test
```

Safe to run against `production` too — this project's production deployment is itself a demo environment, not a real user-facing system, and real signed-up accounts are always preserved (see [What it does](#what-it-does)).

## Why a Maven project instead of a script

Talking to DynamoDB directly needs the AWS SDK for Java, and the deletion logic (which people survive, reconciling meeting-participants alongside each meeting) is worth covering with unit tests the same way [database-repair](../database-repair/README.md)'s repairs are — `DatabaseResetTest` exercises `DatabaseReset` against a fake in-memory DynamoDB client (`mvn test`), independent of real AWS calls. The bounded-concurrency helper it uses to parallelise per-item deletes (`DatabaseResetHandler.runInParallel`) has its own tests too (`DatabaseResetHandlerConcurrencyTest`), identical in shape to the equivalent tests in sample-data-generator and database-repair.
