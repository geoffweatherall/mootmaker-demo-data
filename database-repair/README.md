# database-repair

Runs one-off maintenance repairs directly against a deployed [room-booking-api](https://github.com/geoffweatherall/room-booking-api) environment's Cognito user pool and DynamoDB tables — for fixing up data that the API itself has no way to fix, rather than day-to-day application behaviour.

Unlike [sample-data-generator](../sample-data-generator/README.md), this doesn't go through the GraphQL API at all: what it needs to read and fix (the full list of Cognito users, and the `cognitoSub` link between a Cognito user and their Person record) isn't exposed there, so it talks to Cognito and DynamoDB directly via the AWS SDK.

## Repairs

Currently just one; more may be added here over time.

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
  demo@room-booking.com -> creating Person 'demo'
  e2e-tests@example.com -> creating Person 'e2e-tests'

Done: 2 Person record(s) created, 40 user(s) already had one.
```

## Prerequisites

- Java 25 and Maven (same as [room-booking-api](https://github.com/geoffweatherall/room-booking-api)).
- A `room-booking-api` checkout as a sibling of `room-booking-tools` (i.e. `room-booking-tools` and `room-booking-api` share a parent directory), deployed to the environment you want to target.
- AWS credentials (e.g. an active `~/.aws` profile/SSO session) with `cognito-idp:ListUsers` on the target user pool and `dynamodb:Query` / `dynamodb:PutItem` on its People table and `cognitoSub-index` GSI. This is the first tool in this repo to call AWS APIs directly rather than only through the GraphQL API, so there's no existing IAM policy bundled for it — use whatever credentials you already deploy/administer that environment with.

## Usage

```bash
./run.sh <environment> [--dry-run]
```

For example, `./run.sh test` or `./run.sh test --dry-run`. This reads the target environment's Cognito user pool id, AWS region, and People table name from `room-booking-api`'s Terraform outputs (via its `authenticate.sh`), then builds and runs the tool.

**Unlike `sample-data-generator`, this is safe to run against a real/production environment** — every repair here is additive and idempotent (it only ever creates a missing Person, never deletes or modifies existing data), so `run.sh` has no "refuse prod" check.

## Why a Maven project instead of a script

Talking to Cognito's Admin APIs and DynamoDB directly needs the AWS SDK for Java, and the repair logic (deciding who needs fixing, deriving a name from an email) is worth covering with unit tests the same way [sample-data-generator](../sample-data-generator/README.md)'s `BookingScheduler` is — `CreateMissingPersonsRepairTest` exercises it against fake in-memory Cognito/DynamoDB clients (`mvn test`), independent of real AWS calls.
