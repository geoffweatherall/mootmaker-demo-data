# database-repair

Runs one-off maintenance repairs directly against a deployed [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api) environment's Cognito user pool and DynamoDB tables — for fixing up data that the API itself has no way to fix, rather than day-to-day application behaviour.

Unlike [sample-data-generator](../sample-data-generator/README.md), this doesn't go through the GraphQL API at all: what it needs to read and fix (the full list of Cognito users, the `cognitoSub` link between a Cognito user and their Person record, and the raw contents of the meeting-participants join table) isn't exposed there, so it talks to Cognito and DynamoDB directly via the AWS SDK.

## Repairs

Both repairs run every time you invoke this tool; more may be added here over time.

### 1. Create a Person for every confirmed Cognito user that doesn't have one

Every confirmed Cognito user is supposed to have a linked Person record, created automatically by the API's `PostConfirmationCreatePersonHandler` trigger when they confirm sign-up. Two things can leave a user without one:

- **Users created directly rather than through sign-up** — e.g. the demo user and the e2e test user (both created by Terraform via `aws_cognito_user`) skip the trigger entirely, since it only fires on `ConfirmSignUp`.
- **A transient failure in the trigger itself** — it deliberately swallows its own errors rather than retrying or failing sign-up (see the API README's "Displaying the signed-in user's name" section), so a DynamoDB hiccup at exactly the wrong moment can leave a confirmed user without a Person.

This repair lists every confirmed Cognito user (skipping `UNCONFIRMED` ones — they haven't finished sign-up, so not having a Person yet is correct, not a bug), checks each one against the People table's `cognitoSub-index` GSI, and creates a Person for any that are missing one. The new Person's name is set to the part of the user's email address before the `@` — a reasonable one-off backfill default, **not** a re-implementation of the trigger's own behaviour (which uses the Cognito `name` attribute — unavailable here for users, like the demo/e2e ones, who never had one set).

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

This repair recomputes the exact participant rows every meeting *should* have from the meetings table, compares that against what's actually in meeting-participants, creates whatever's missing, and removes any row that doesn't belong (its meeting no longer exists, or it's not actually one of that meeting's participants). It never touches the meetings table itself — only meeting-participants, which is safe to fully regenerate since nothing else is a source of truth for it.

Expect output like:

```
Repair: rebuilding meeting-participants from the meetings table...
Found 604 meeting(s).
  creating participant row: person a1b2c3..., meeting f9e8d7...
  ...

Done: 612 participant row(s) created, 0 removed, 1200 already correct.
```

## Prerequisites

- Java 25 and Maven (same as [mootmaker-api](https://github.com/geoffweatherall/mootmaker-api)).
- A `mootmaker-api` checkout as a sibling of `mootmaker-tools` (i.e. `mootmaker-tools` and `mootmaker-api` share a parent directory), deployed to the environment you want to target.
- AWS credentials (e.g. an active `~/.aws` profile/SSO session) with:
  - `cognito-idp:ListUsers` on the target user pool.
  - `dynamodb:Query` / `dynamodb:PutItem` on the People table and its `cognitoSub-index` GSI.
  - `dynamodb:Scan` on the Meetings table, and `dynamodb:Scan` / `dynamodb:PutItem` / `dynamodb:DeleteItem` on the meeting-participants table.

  This is the first tool in this repo to call AWS APIs directly rather than only through the GraphQL API, so there's no existing IAM policy bundled for it — use whatever credentials you already deploy/administer that environment with.

## Usage

```bash
./run.sh <environment> [--dry-run]
```

For example, `./run.sh test` or `./run.sh test --dry-run`. This reads the target environment's Cognito user pool id, AWS region, and People/Meetings/meeting-participants table names from `mootmaker-api`'s Terraform outputs (via its `authenticate.sh`), then builds and runs the tool. Both repairs run on every invocation.

**Unlike `sample-data-generator`, this is safe to run against a real/production environment** — every repair here only ever touches data with no other source of truth: repair #1 only ever *creates* a missing Person, never deleting or modifying existing data; repair #2 only ever creates or removes rows in the *derived* meeting-participants index, never the meetings table itself, which remains the sole source of truth throughout. So `run.sh` has no "refuse prod" check.

## Why a Maven project instead of a script

Talking to Cognito's Admin APIs and DynamoDB directly needs the AWS SDK for Java, and the repair logic (deciding who needs fixing, deriving a name from an email, reconciling meeting-participants against the meetings table) is worth covering with unit tests the same way [sample-data-generator](../sample-data-generator/README.md)'s `MeetingScheduler` is — `CreateMissingPersonsRepairTest` and `RebuildMeetingParticipantsRepairTest` exercise the two repairs against fake in-memory Cognito/DynamoDB clients (`mvn test`), independent of real AWS calls.
