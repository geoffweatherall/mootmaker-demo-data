# database-repair

Runs one-off maintenance repairs directly against a deployed [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) environment's Cognito user pool and DynamoDB tables — for fixing up data that the API itself has no way to fix, rather than day-to-day application behaviour.

Unlike [sample-data-generator](../sample-data-generator/README.md), this doesn't go through the GraphQL API at all: what it needs to read and fix (the full list of Cognito users, the `cognitoSub` link between a Cognito user and their Person record, and the raw contents of the meeting-participants join table) isn't exposed there, so it talks to Cognito and DynamoDB directly via the AWS SDK.

Deployed as its own AWS Lambda function, one per target environment, and invoked on demand via `./run.sh <environment> [--dry-run]` rather than run continuously - see [How it is deployed](#how-it-is-deployed) below.

## Repairs

Both repairs run every time you invoke this tool; more may be added here over time. They touch entirely different tables (People vs. Meetings/meeting-participants), so the Lambda runs them **concurrently** rather than one after the other - see [How it is deployed](#how-it-is-deployed).

### 1. Create a Person for every confirmed Cognito user that doesn't have one

Every confirmed Cognito user is supposed to have a linked Person record, created automatically by the API's `PostConfirmationCreatePersonHandler` trigger when they confirm sign-up. Two things can leave a user without one:

- **Users created directly rather than through sign-up** — e.g. the demo user and the e2e test user (both created by Terraform via `aws_cognito_user`) skip the trigger entirely, since it only fires on `ConfirmSignUp`.
- **A transient failure in the trigger itself** — it deliberately swallows its own errors rather than retrying or failing sign-up (see the API README's "Displaying the signed-in user's name" section), so a DynamoDB hiccup at exactly the wrong moment can leave a confirmed user without a Person.

This repair lists every confirmed Cognito user (skipping `UNCONFIRMED` ones — they haven't finished sign-up, so not having a Person yet is correct, not a bug), checks each one against the People table's `cognitoSub-index` GSI, and creates a Person for any that are missing one - each user's check-and-create is independent of every other user's, so this runs on a bounded pool of up to 8 concurrent requests rather than one user at a time (see [How it is deployed](#how-it-is-deployed)). The new Person's name is set to the part of the user's email address before the `@` — a reasonable one-off backfill default, **not** a re-implementation of the trigger's own behaviour (which uses the Cognito `name` attribute — unavailable here for users, like the demo/e2e ones, who never had one set).

Pass `--dry-run` to see what it would do without writing anything:

```bash
./run.sh test --dry-run
```

Expect output like:

```
Running database repairs against 'test'...
Repair: creating a Person for every confirmed Cognito user that doesn't have one...
Found 42 confirmed Cognito user(s).
  demo@mootmaker.com -> creating Person 'demo'
  e2e-tests@example.com -> creating Person 'e2e-tests'

Done: 2 Person record(s) created, 40 user(s) already had one.
```

### 2. Rebuild meeting-participants from the meetings table

mootmaker-api's meetings table stores a meeting's `roomId`, `organiserId`, and `attendeeIds`, but `Query.meetings`' `personId` filter needs to answer "which meetings is this person organiser of or an attendee on" without scanning every meeting's `attendeeIds` list — so `CreateMeetingHandler` also writes one row per (meeting, organiser-or-attendee) pair to a separate `meeting-participants` table, in the same transaction as the meeting itself. The meetings table is the source of truth; meeting-participants is a **derived index** of it, and this repair keeps the two in sync:

- **Existing meetings from before meeting-participants existed** have no rows there at all until this repair runs once against an environment that already had meetings when the feature was deployed.
- **Drift** — a manual data fix, a restored table, or any other way the two tables could end up disagreeing — is caught the same way.

This repair recomputes the exact participant rows every meeting *should* have from the meetings table, compares that against what's actually in meeting-participants, creates whatever's missing, and removes any row that doesn't belong (its meeting no longer exists, or it's not actually one of that meeting's participants). Every row created or removed is independent of every other one, so - like repair #1 - both the create and remove passes run on a bounded pool of up to 8 concurrent requests. It never touches the meetings table itself — only meeting-participants, which is safe to fully regenerate since nothing else is a source of truth for it.

Expect output like:

```
Repair: rebuilding meeting-participants from the meetings table...
Found 604 meeting(s).
  creating participant row: person a1b2c3..., meeting f9e8d7...
  ...

Done: 612 participant row(s) created, 0 removed, 1200 already correct.
```

## How it is deployed

```
./run.sh <environment> ──aws lambda invoke──▶ Lambda (Java) ──AWS SDK──▶ Cognito + DynamoDB (target environment)
```

- `DatabaseRepairHandler` is the Lambda entry point. It reads `event.dryRun` (a boolean) from the invoke payload - `run.sh` sets it from the `--dry-run` flag - and runs both repairs, each on its own thread since they touch different tables. Each repair's own per-item AWS calls (one check-and-create per Cognito user; one put/delete per meeting-participants row) are themselves spread across a bounded pool of up to 8 concurrent requests (`DatabaseRepairHandler.runInParallel`). Together, this is what keeps a run comfortably inside a Lambda invocation's 15-minute hard ceiling as the number of users/meetings grows, rather than the function's configured timeout doing that work.
- The function reads `COGNITO_USER_POOL_ID`, `PEOPLE_TABLE_NAME`, `MEETINGS_TABLE_NAME`, and `MEETING_PARTICIPANTS_TABLE_NAME` from its own environment variables (set by `deploy.sh` from the target environment's Terraform outputs), and its region from the `AWS_REGION` variable Lambda sets automatically.
- Unlike sample-data-generator, this function's IAM role is granted real AWS permissions, scoped to exactly the target environment's resources (see [deploy/terraform/iam.tf](deploy/terraform/iam.tf)):
  - `cognito-idp:ListUsers` on the target user pool.
  - `dynamodb:Query` / `dynamodb:PutItem` on the People table and its `cognitoSub-index` GSI.
  - `dynamodb:Scan` on the Meetings table, and `dynamodb:Scan` / `dynamodb:PutItem` / `dynamodb:DeleteItem` on the meeting-participants table.

  Now that this runs as its own Lambda with its own role, it no longer depends on whatever ambient AWS credentials a developer happens to have active - unlike when this was a locally-run tool.
- Runtime is Java 25, 512 MB, 300 s timeout.
- The response payload is a small JSON summary (counts from both repairs); the full step-by-step log (the same output shown above) goes to CloudWatch Logs, under `/aws/lambda/<environment>-mootmaker-database-repair`.

## Directory structure

| Path | Contents |
|---|---|
| [impl/](impl/) | Maven project with the Lambda handler (`DatabaseRepairHandler`), the two repairs (`CreateMissingPersonsRepair`, `RebuildMeetingParticipantsRepair`), the duplicated model records, and unit tests. Builds the shaded jar deployed to Lambda. |
| [deploy/terraform/](deploy/terraform/) | Terraform for the Lambda function, its execution role, and the IAM policy above. State is stored remotely in S3, one state file per environment (the same bucket `mootmaker-api`/`mootmaker-webapp` use - see [backend.hcl](deploy/terraform/backend.hcl)). |

### Bash scripts

All scripts live in the project root and are run from there:

| Script | What it does | How to run |
|---|---|---|
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), reads the target environment's Cognito/DynamoDB settings from `mootmaker-api`'s Terraform outputs, then `terraform init` + `terraform apply -auto-approve` to create/update the Lambda function and its IAM role/policy **for the given environment**. Creates real AWS resources - run deliberately. | `./deploy.sh <environment>` |
| [run.sh](run.sh) | Invokes the already-deployed Lambda for the given environment via `aws lambda invoke`, prints the tail of its CloudWatch log output and the JSON summary it returns. Both repairs run on every invocation. | `./run.sh <environment> [--dry-run]` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` - deletes the Lambda function and its IAM role/policy for the given environment. Does not touch the target `mootmaker-api` environment. Prompts for confirmation. | `./undeploy.sh <environment>` |

## Prerequisites

- Java 25 and Maven, Terraform ≥ 1.10, and the AWS CLI (same as [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api)), plus `jq` (used by `run.sh` to parse the Lambda invoke response).
- A `mootmaker-api` checkout as a sibling of `mootmaker-tools` (i.e. `mootmaker-tools` and `mootmaker-api` share a parent directory), deployed to the environment you want to target.
- AWS credentials able to run `deploy.sh`/`undeploy.sh` (i.e. to manage the Lambda function and IAM role/policy themselves) - the same credentials used to deploy `mootmaker-api`/`mootmaker-webapp` work. `run.sh` only needs `lambda:InvokeFunction` on the deployed function.

## Usage

```bash
# Deploy (build + terraform apply) to an environment, e.g. "test" or your own name
./deploy.sh test

# Run both repairs against that environment
./run.sh test

# Or see what it would do first, without writing anything
./run.sh test --dry-run

# Tear the Lambda down when you're done with it (the target mootmaker-api environment is untouched)
./undeploy.sh test
```

**Unlike `sample-data-generator`, this is safe to run against a real/production environment** — every repair here only ever touches data with no other source of truth: repair #1 only ever *creates* a missing Person, never deleting or modifying existing data; repair #2 only ever creates or removes rows in the *derived* meeting-participants index, never the meetings table itself, which remains the sole source of truth throughout. So `run.sh` has no "refuse prod" check.

## Why a Maven project instead of a script

Talking to Cognito's Admin APIs and DynamoDB directly needs the AWS SDK for Java, and the repair logic (deciding who needs fixing, deriving a name from an email, reconciling meeting-participants against the meetings table) is worth covering with unit tests the same way [sample-data-generator](../sample-data-generator/README.md)'s `MeetingScheduler` is — `CreateMissingPersonsRepairTest` and `RebuildMeetingParticipantsRepairTest` exercise the two repairs against fake in-memory Cognito/DynamoDB clients (`mvn test`), independent of real AWS calls. The bounded-concurrency helper both repairs use to parallelise their per-item calls (`DatabaseRepairHandler.runInParallel`) has its own tests too (`DatabaseRepairHandlerConcurrencyTest`), the same as sample-data-generator's equivalent.
