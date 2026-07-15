# sample-data-generator

Resets a deployed [room-booking-api](https://github.com/geoffweatherall/room-booking-api) environment and populates it with realistic-looking sample data, so a non-production environment (or a fresh personal sandbox) has something worth looking at without manually clicking through the webapp.

## What it does

Running the tool, in order:

1. Calls the API's `reset` mutation. This deletes all rooms and bookings, and every person **except** those linked to a real Cognito account (e.g. anyone who's actually signed up through the webapp, and the e2e test user, are left alone — see the [API README](https://github.com/geoffweatherall/room-booking-api#reset-and-real-user-accounts)).
2. Looks up whoever is left after that reset — i.e. real signed-up users — and includes them alongside the newly-created people below when booking meetings, so real accounts show up with a realistic-looking calendar too, not just sample data.
3. Creates **40 people**, with realistic full names (via [datafaker](https://www.datafaker.net/)) — enough that the room-filling meetings below can actually be staffed alongside everything else. This count doesn't change based on how many real users already exist; they're additional people to book, not a replacement for any of the 40.
4. Creates **10 rooms**, each with a meaningful name (e.g. `Everest`, `Boardroom`, `The Hub`) and a random capacity between 4 and 20.
5. Creates **bookings across every business day from a week ago to seven weeks from now** (Monday-Friday only): each room gets 0-2 sequential meetings per day (so the exact total varies run to run), every meeting within business hours (08:00-17:00), with a realistic subject (e.g. `Sprint Planning`, `Client Onboarding Call`), an organiser plus **at least one** attendee (drawn from both the newly-created people and any existing real users), and a randomly chosen duration (15/30/45/60/90/120 minutes) on the API's required 5-minute boundary.

   Scheduling rules, enforced by `BookingScheduler`:
   - **A room is never double-booked**: each room's meetings for a day are placed back-to-back or with gaps, never overlapping.
   - **A person (organiser or attendee) is never in two meetings at once**, tracked across *every* room and day - so nobody ends up double-booked just because they were picked for two different rooms' meetings at the same time.
   - **Meetings in different rooms may legitimately overlap in time** - that's realistic (two unrelated meetings happening at once in different rooms) and is only prevented between meetings that share a room or a person.
   - **Each room's first meeting of the day starts at a random point in the day**, not always 08:00, so meetings don't all bunch up at the start of business hours.
   - **At least half of a person's meetings are followed by a real gap** before their next one, rather than being back-to-back, so a person's calendar looks like a real one rather than a packed schedule.
   - **At least half of all meetings use at least half the room's capacity** (a mix of small catch-ups and larger, room-filling sessions), with every meeting still respecting the capacity limit.

Creating people, rooms, and bookings each run up to **8 requests concurrently** rather than one at a time - the full schedule is worked out up front with no overlapping room or person times anywhere in it (see the scheduling rules above), so the order the ~600 `createBooking` calls actually reach the server in doesn't matter, and they don't need to wait on one another. 8 is a deliberately modest cap - enough to meaningfully cut down the several hundred network round trips this involves, without throwing a burst of concurrent traffic at what's usually a small demo deployment.

Expect output like:

```
Resetting environment...
Found 2 existing person(s) with a Cognito account; including them when booking meetings...
  Jane Doe
  John Smith
Creating 40 people...
  Ada Lovelace
  ...
Creating 10 rooms...
  Everest (capacity 12)
  ...
Creating 604 bookings from 7 days ago to 49 days ahead...
  Sprint Planning - 2026-07-16T09:15:00 to 2026-07-16T10:15:00
  ...

Done: 40 new people (+2 existing Cognito-linked person(s)), 10 rooms, 604 bookings created.
```

(The "Found ... existing person(s)" line is only printed when there are any — a freshly bootstrapped environment with no real sign-ups yet won't show it.)

## Prerequisites

- Java 25 and Maven (same as [room-booking-api](https://github.com/geoffweatherall/room-booking-api)).
- A `room-booking-api` checkout as a sibling of `room-booking-tools` (i.e. `room-booking-tools` and `room-booking-api` share a parent directory), deployed to the environment you want to target.

## Usage

```bash
./run.sh <environment>
```

For example, `./run.sh test`. This reads the target environment's Cognito/GraphQL settings from `room-booking-api`'s Terraform outputs (via its `authenticate.sh`, using the same client_credentials M2M auth as the API's own acceptance tests — no username or password involved), then builds and runs the generator.

**`run.sh` refuses to run against any environment whose name starts with `prod`** (case-insensitive), regardless of any other argument — this tool calls `reset`, so it must never be pointed at a production environment.

## Why a Maven project instead of a script

The generator needs to call `createBooking`/`createRoom`/`createPerson` with values that satisfy the API's validation rules (5-minute time boundaries, room capacity, no overlapping bookings for the same room) — see the [API README's Validation section](https://github.com/geoffweatherall/room-booking-api#validation). That scheduling logic (`BookingScheduler`) is plain, dependency-free Java with its own unit tests (`mvn test`), independent of the GraphQL calls it feeds into (`SampleDataGenerator`/`GraphQlClient`). The bounded-concurrency helper the generator uses to run those calls in parallel (`SampleDataGenerator.runInParallel`) has its own tests too (`SampleDataGeneratorConcurrencyTest`), covering that every item still gets processed exactly once, that work is actually spread across more than one thread, and that a failure partway through still surfaces after everything else has finished.
