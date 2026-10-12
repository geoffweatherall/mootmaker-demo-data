# Testing strategy

How mootmaker-demo-data is tested. The layers across the whole system are in the hub's
[testing overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/testing.md).

## Layers

- **Unit tests** (`impl/src/test/`, run via `mvn -f impl/pom.xml clean package`): fast, no AWS
  involved. Cover the scheduling algorithm's invariants, the four concerns' top-up arithmetic
  (including the already-at-target and over-target cases that make a run repeatable), payload toggle
  parsing, and the bounded-parallelism helper. They also check **every GraphQL operation against
  `mootmaker-api`'s schema**: `OperationsSchemaTest` validates each constant in `Operations`, and
  `FakeGraphQlClient` refuses any request, variables included, that the schema would reject, so the
  fake cannot answer a request the real API would refuse.
  The schema is read from the sibling `../mootmaker-api` checkout (or `MOOTMAKER_SCHEMA`), and the
  tests **fail** if it is missing rather than skipping.
- **Acceptance tests** (`verify/`, JUnit `*IT.java`, run via `./verify.sh <environment>`): exercise
  the **deployed** Lambda against a **deployed** `mootmaker-api`, over real Cognito M2M auth, real
  AppSync and real DynamoDB.

## What the acceptance tests assert, and why

They assert **invariants, not exact values**. Checking that a run created exactly 487 meetings would
just restate the implementation and break on every tuning change. What matters is that the data is
*plausible*:

- every business day in the window has at least one meeting, and no meeting falls on a weekend;
- no room is double-booked;
- no organiser is ever double-booked, as organiser or attendee; an attendee may be invited into up
  to 2 simultaneous overlapping meetings, but never more (see
  [mootmaker/designs/archive/realistic-demo-meeting-schedule.md](https://github.com/geoffweatherall/mootmaker/blob/main/designs/archive/realistic-demo-meeting-schedule.md));
- nothing falls outside business hours (08:00–17:00);
- no meeting exceeds its room's capacity, and every meeting has an organiser and an attendee;
- the people and room targets are met exactly, not exceeded.

These are the rules `MeetingScheduler` claims to enforce, checked against data the API itself
accepted — which is what makes them meaningful rather than circular. This component writes
exclusively through GraphQL, so anything it produced is by definition data the API's own validation
allowed.

Two more earn their place for a different reason:

- **A second run must change nothing.** Every concern is *defined* by doing nothing once
  their target is met, so this is the single assertion most likely to catch a regression in any of
  them.
- **A disabled concern must do nothing.** Proves the toggle actually reaches the concern — the
  mechanism that lets a misbehaving one be switched off without a redeploy.

## The dependency on a deployed API

`verify.sh` needs **both** `mootmaker-api` and this component deployed to the target environment. It
resets the environment (via the api's `database-reset`) and then seeds it with a real Lambda
invocation, so it is destructive; it refuses to run against `production`.

That is unavoidable for a component whose whole job is to call another one, and it makes this the
slowest suite to stand up, so **point `verify.sh` at an existing ephemeral environment** rather than
creating one per run:

```bash
../mootmaker-ephemeral-envs/create-ephemeral-env.sh claude --with-demo-data
./verify.sh <the name it printed>
```

## Testing notes

- **Every acceptance run exercises the full-window seed.** The environment is reset first, so every
  day in the window is empty and the run fills about 35 business days at once.
- **Client-side timeouts have to clear 900 seconds.** The Lambda's ceiling is the AWS maximum, and
  both the AWS CLI (60s default) and the AWS SDK default to far less. A run that exceeds the
  caller's timeout is reported as a failure while the Lambda completes regardless.
  `DemoDataLambda` sets this explicitly.
- **Business hours are exactly the range generated data fills**, so in a seeded environment no time
  slot is free by construction. Any test needing a bookable slot has to pick a room that happens to
  be free rather than a time.
