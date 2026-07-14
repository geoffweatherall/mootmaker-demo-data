# sample-data-generator

Resets a deployed [room-booking-api](https://github.com/geoffweatherall/room-booking-api) environment and populates it with realistic-looking sample data, so a non-production environment (or a fresh personal sandbox) has something worth looking at without manually clicking through the webapp.

## What it does

Running the tool, in order:

1. Calls the API's `reset` mutation. This deletes all rooms and bookings, and every person **except** those linked to a real Cognito account (e.g. anyone who's actually signed up through the webapp, and the e2e test user, are left alone — see the [API README](https://github.com/geoffweatherall/room-booking-api#reset-and-real-user-accounts)).
2. Creates **10 people**, with realistic full names (via [datafaker](https://www.datafaker.net/)).
3. Creates **10 rooms**, each with a meaningful name (e.g. `Everest`, `Boardroom`, `The Hub`) and a random capacity between 4 and 20.
4. Creates **bookings over the next 3 business days**: each room gets 1-2 sequential meetings per day (so the exact total varies run to run, typically 30-45), every meeting within business hours (08:00-17:00), with a realistic subject (e.g. `Sprint Planning`, `Client Onboarding Call`), an organiser plus **at least one** attendee (sized to fit the room's capacity), and a randomly chosen duration (15/30/45/60/90/120 minutes) on the API's required 5-minute boundary.

   Scheduling rules, enforced by `BookingScheduler`:
   - **A room is never double-booked**: each room's meetings for a day are placed back-to-back or with gaps, never overlapping.
   - **A person (organiser or attendee) is never in two meetings at once**, tracked across *every* room and day - so nobody ends up double-booked just because they were picked for two different rooms' meetings at the same time.
   - **Meetings in different rooms may legitimately overlap in time** - that's realistic (two unrelated meetings happening at once in different rooms) and is only prevented between meetings that share a room or a person.

Expect output like:

```
Resetting environment...
Creating 10 people...
  Ada Lovelace
  ...
Creating 10 rooms...
  Everest (capacity 12)
  ...
Creating 34 bookings over the next 3 days...
  Sprint Planning - 2026-07-16T09:15:00 to 2026-07-16T10:15:00
  ...

Done: 10 people, 10 rooms, 34 bookings created.
```

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

The generator needs to call `createBooking`/`createRoom`/`createPerson` with values that satisfy the API's validation rules (5-minute time boundaries, room capacity, no overlapping bookings for the same room) — see the [API README's Validation section](https://github.com/geoffweatherall/room-booking-api#validation). That scheduling logic (`BookingScheduler`) is plain, dependency-free Java with its own unit tests (`mvn test`), independent of the GraphQL calls it feeds into (`SampleDataGenerator`/`GraphQlClient`).
