package com.mootmaker.demodata;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.mootmaker.demodata.MeetingScheduler.GeneratedMeeting;
import com.mootmaker.demodata.MeetingScheduler.RoomInfo;

/**
 * Tops a deployed mootmaker-api environment up with realistic demo data, across three independent
 * concerns - people, rooms and meetings - each of which is a no-op once its target is already met.
 * That is what makes a run safe to repeat: seeding a freshly-deployed environment and topping up
 * `production` on a schedule are the same operation, differing only in how much they find already
 * done.
 *
 * <p><b>This tool never deletes anything.</b> It has no reset path and no way to reach one - see
 * mootmaker/designs/archive/demo-data-component.md. Clearing an environment is a separate,
 * deliberate invocation of mootmaker-api's own database-reset Lambda, run by hand before this one.
 * Its predecessor sample-data-generator reset the database as the first step of every run, behind a
 * script called {@code run.sh}; that is exactly the property this design removed.
 *
 * <p>Deployed as a Lambda, invoked daily by an EventBridge schedule (see deploy/terraform/) and on
 * demand via {@code aws lambda invoke} - see this project's README.
 */
final class DemoData {

  /**
   * Every meeting needs an organiser plus at least one attendee - mirrors MeetingScheduler's own
   * floor.
   */
  private static final int MIN_BOOKABLE_PEOPLE = 2;

  private static final int MIN_ROOM_CAPACITY = 4;
  private static final int MAX_ROOM_CAPACITY = 20;

  private static final DateTimeFormatter MEETING_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /**
   * Each create* call is an independent network round trip (its own AppSync request + Lambda
   * invocation), and {@link MeetingScheduler} has already resolved a conflict-free schedule up
   * front, so the calls can safely run in parallel rather than one at a time - capped well below
   * AppSync/Lambda's default concurrency limits so this doesn't look like a burst of traffic
   * against a small demo deployment or exhaust the JVM's HTTP connection pool.
   */
  private static final int MAX_CONCURRENT_REQUESTS = 8;

  /**
   * The API's own per-call cap on {@code createMeetings}, mirrored rather than imported - this tool
   * depends on the published schema, not on mootmaker-api's Java.
   *
   * <p>Ninety-nine because the day item and one id-to-date pointer per new meeting are written in a
   * single DynamoDB transaction, and that caps at 100 items. If it ever changes, the schema's own
   * documentation on {@code createMeetings} is the source of truth.
   */
  private static final int MAX_MEETINGS_PER_BULK_CREATE = 99;

  /**
   * Bounds for a guaranteed meeting's start hour, and its length. Well inside the 08:00-17:00
   * business day the scheduler uses, because the point is a meeting that certainly succeeds rather
   * than one that looks realistically distributed - the room is free all day, so nothing here has
   * to avoid anything.
   */
  private static final int GUARANTEED_MEETING_EARLIEST_HOUR = 9;

  private static final int GUARANTEED_MEETING_LATEST_HOUR = 15;

  private static final int GUARANTEED_MEETING_MINUTES = 30;

  private DemoData() {}

  /**
   * How much data a run aims for, and how wide a window it fills. Read from environment variables
   * set by Terraform rather than from the invoke payload, deliberately: a mistyped ad hoc invoke
   * can turn a concern off, but can never ask for 4,000 people.
   */
  record Targets(int people, int rooms, int daysInPast, int weeksAhead) {

    static Targets fromEnvironment() {
      return from(System::getenv);
    }

    /**
     * Package-private, taking the lookup as a parameter, so tests can exercise it without setting
     * real environment variables.
     */
    static Targets from(final UnaryOperator<String> env) {
      return new Targets(
          intEnv(env, "TARGET_PEOPLE", 40),
          intEnv(env, "TARGET_ROOMS", 10),
          intEnv(env, "DAYS_IN_PAST", 7),
          intEnv(env, "WEEKS_AHEAD", 6));
    }

    private static int intEnv(
        final UnaryOperator<String> env, final String name, final int defaultValue) {
      final String value = env.apply(name);
      if (value == null || value.isBlank()) {
        return defaultValue;
      }
      try {
        return Integer.parseInt(value.trim());
      } catch (final NumberFormatException e) {
        throw new IllegalStateException(name + " must be an integer, got: '" + value + "'", e);
      }
    }
  }

  /**
   * Which of the three concerns this run should attempt. Every one defaults to enabled, so a
   * scheduled invocation with an empty payload does all three; a concern can be switched off per
   * invocation (or, if one turns out to be misbehaving, without redeploying) via the payload.
   */
  record Concerns(boolean people, boolean rooms, boolean meetings) {

    static final Concerns ALL = new Concerns(true, true, true);

    /**
     * Reads {@code {"people": false}}-style flags from the invoke payload, defaulting each to true.
     */
    static Concerns fromPayload(final Map<String, Object> payload) {
      if (payload == null || payload.isEmpty()) {
        return ALL;
      }
      return new Concerns(
          flag(payload, "people"), flag(payload, "rooms"), flag(payload, "meetings"));
    }

    private static boolean flag(final Map<String, Object> payload, final String name) {
      final Object value = payload.get(name);
      return switch (value) {
        case null -> true;
        case Boolean bool -> bool;
        case String string -> Boolean.parseBoolean(string);
        default ->
            throw new IllegalArgumentException(
                "Payload field '" + name + "' must be a boolean, got: " + value);
      };
    }
  }

  /** Summary of a completed run, returned as the Lambda invocation's response payload. */
  record Summary(
      int peopleCreated,
      int roomsCreated,
      int weekdaysToppedUp,
      int meetingsCreated,
      int guaranteedMeetingsCreated) {}

  static Summary run(
      final GraphQlClient client,
      final Targets targets,
      final Concerns concerns,
      final List<String> guaranteedPersonIds) {
    final Random random = new Random();

    final int peopleCreated = concerns.people() ? topUpPeople(client, targets.people(), random) : 0;
    final int roomsCreated = concerns.rooms() ? topUpRooms(client, targets.rooms(), random) : 0;

    if (!concerns.meetings()) {
      System.out.println("Meetings concern disabled for this run - skipping.");
      return new Summary(peopleCreated, roomsCreated, 0, 0, 0);
    }
    final Summary meetingSummary = topUpMeetings(client, targets, random);

    // AFTER the top-up, not before: the top-up fills days that have no meetings at all, which may
    // itself give a guaranteed person their meeting for that day. Running this first would create
    // one and then have the top-up skip the day as no longer empty - the same meeting count by a
    // less obvious route, and a day whose only meeting is the guaranteed one.
    final int guaranteed = guaranteeMeetings(client, guaranteedPersonIds, targets, random);

    return new Summary(
        peopleCreated,
        roomsCreated,
        meetingSummary.weekdaysToppedUp(),
        meetingSummary.meetingsCreated(),
        guaranteed);
  }

  // --- People ---------------------------------------------------------------------------

  /**
   * Creates however many people are needed to reach {@code target}, and none if the environment
   * already has that many.
   *
   * <p>The count is against the <b>total</b> number of people, not "demo people" specifically:
   * {@code Person} exposes no Cognito linkage in the GraphQL schema, and this tool reaches the
   * system only through that schema, so it genuinely cannot tell a generated person from a real
   * signed-up one (see the design's "Trade-offs and decisions"). The consequence is deliberate and
   * benign - in an environment where real sign-ups have already passed the target, no demo people
   * are created, because there are already enough people to book meetings with.
   */
  static int topUpPeople(final GraphQlClient client, final int target, final Random random) {
    final int existing = fetchPersonIds(client).size();
    final int toCreate = Math.max(0, target - existing);
    if (toCreate == 0) {
      System.out.println(
          "People: " + existing + " already exist (target " + target + ") - nothing to do.");
      return 0;
    }
    System.out.println(
        "People: " + existing + " exist, creating " + toCreate + " to reach " + target + "...");

    // { person { ... } errors }, matching createRoom below. CreatePersonResult has never had id
    // or name directly on it - the previous selection was simply invalid, and nothing caught it
    // because this path had no acceptance coverage and the fake client mirrored the same wrong
    // shape back.
    final String mutation =
        "mutation CreatePerson($person: PersonInput!) { "
            + "createPerson(person: $person) { person { id name } errors } }";
    // Random isn't safe for concurrent use, so the names are drawn up front, sequentially;
    // only the network calls below run in parallel. The names are distinct by construction,
    // which is what lets the loop below index by position rather than by name.
    final List<String> names = SampleData.personNames(toCreate, random);

    runInParallel(
        IntStream.range(0, toCreate).boxed().toList(),
        i -> {
          final JsonNode result =
              client.execute(mutation, Map.of("person", Map.of("name", names.get(i))));
          failIfErrors(result.get("createPerson"), "createPerson(" + names.get(i) + ")");
          System.out.println("  " + result.get("createPerson").get("person").get("name").asText());
        });
    return toCreate;
  }

  // --- Rooms ----------------------------------------------------------------------------

  /**
   * Creates however many rooms are needed to reach {@code target}, and none if the environment
   * already has that many. Names come from {@link SampleData#ROOM_NAMES}, skipping any already in
   * use so a top-up never creates a second "Everest"; if the curated list runs out, names get a
   * numeric suffix rather than failing the run.
   */
  static int topUpRooms(final GraphQlClient client, final int target, final Random random) {
    final List<RoomDetail> existing = fetchRooms(client);
    final int toCreate = Math.max(0, target - existing.size());
    if (toCreate == 0) {
      System.out.println(
          "Rooms: " + existing.size() + " already exist (target " + target + ") - nothing to do.");
      return 0;
    }
    System.out.println(
        "Rooms: "
            + existing.size()
            + " exist, creating "
            + toCreate
            + " to reach "
            + target
            + "...");

    final Set<String> usedNames =
        existing.stream().map(RoomDetail::name).collect(Collectors.toSet());
    final List<String> names = availableRoomNames(usedNames, toCreate, random);

    // Random isn't safe for concurrent use, so capacities are generated up front, sequentially.
    final int[] capacities = new int[toCreate];
    for (int i = 0; i < toCreate; i++) {
      capacities[i] = MIN_ROOM_CAPACITY + random.nextInt(MAX_ROOM_CAPACITY - MIN_ROOM_CAPACITY + 1);
    }

    final String mutation =
        "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity"
            + " } errors } }";
    runInParallel(
        IntStream.range(0, toCreate).boxed().toList(),
        i -> {
          final String name = names.get(i);
          final JsonNode result =
              client.execute(
                  mutation, Map.of("room", Map.of("name", name, "capacity", capacities[i])));
          final JsonNode payload = result.get("createRoom");
          failIfErrors(payload, "createRoom(" + name + ")");
          final JsonNode room = payload.get("room");
          System.out.println(
              "  "
                  + room.get("name").asText()
                  + " (capacity "
                  + room.get("capacity").asInt()
                  + ")");
        });
    return toCreate;
  }

  /**
   * {@code count} room names not already in {@code usedNames}, drawn from the curated list in a
   * random order. Package-private so tests can exercise the exhaustion path directly.
   */
  static List<String> availableRoomNames(
      final Set<String> usedNames, final int count, final Random random) {
    final List<String> unused = new ArrayList<>(SampleData.ROOM_NAMES);
    unused.removeAll(usedNames);
    Collections.shuffle(unused, random);

    final List<String> names = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (i < unused.size()) {
        names.add(unused.get(i));
      } else {
        // The curated list is exhausted. Suffix from the full list rather than failing:
        // a demo environment with more rooms than curated names is unusual but harmless,
        // and a run that half-succeeded then threw would be worse than one that finished.
        final String base = SampleData.ROOM_NAMES.get(i % SampleData.ROOM_NAMES.size());
        int suffix = 2;
        String candidate = base + " " + suffix;
        while (usedNames.contains(candidate) || names.contains(candidate)) {
          candidate = base + " " + ++suffix;
        }
        names.add(candidate);
      }
    }
    return names;
  }

  // --- Meetings -------------------------------------------------------------------------

  /**
   * Fills every weekday in the window that currently has no meetings at all. The window reaches
   * {@code daysInPast} days behind today as well as {@code weeksAhead} weeks in front of it, so a
   * freshly-seeded environment has a calendar with history rather than one that starts empty today.
   * A past day is topped up at most once: once it has meetings, the same "this day has no meetings"
   * guard leaves it alone forever.
   *
   * <p>Package-private (rather than private) so tests can exercise it directly, like
   * weekdaysBetween.
   */
  static Summary topUpMeetings(
      final GraphQlClient client, final Targets targets, final Random random) {
    final LocalDate today = LocalDate.now();
    final LocalDate windowStart = today.minusDays(targets.daysInPast());
    final LocalDate windowEnd = today.plusDays((long) targets.weeksAhead() * 7);

    System.out.println(
        "Checking for empty weekdays between "
            + windowStart
            + " and "
            + windowEnd.minusDays(1)
            + "...");

    // Rooms, people and existing meetings are three independent reads, so they run
    // concurrently rather than one after another.
    final ExecutorService executor = Executors.newFixedThreadPool(3);
    final List<RoomDetail> rooms;
    final List<String> personIds;
    final Set<LocalDate> datesWithMeetings;
    try {
      final Future<List<RoomDetail>> roomsFuture = executor.submit(() -> fetchRooms(client));
      final Future<List<String>> peopleFuture = executor.submit(() -> fetchPersonIds(client));
      final Future<Set<LocalDate>> meetingDatesFuture =
          executor.submit(() -> fetchDatesWithMeetings(client, windowStart, windowEnd));

      rooms = getResult(roomsFuture);
      personIds = getResult(peopleFuture);
      datesWithMeetings = getResult(meetingDatesFuture);
    } finally {
      executor.shutdown();
    }

    final List<LocalDate> targetDays =
        weekdaysBetween(windowStart, windowEnd).stream()
            .filter(day -> !datesWithMeetings.contains(day))
            .toList();

    if (targetDays.isEmpty()) {
      System.out.println(
          "Every weekday in the window already has at least one meeting - nothing to do.");
      return new Summary(0, 0, 0, 0, 0);
    }
    System.out.println("Found " + targetDays.size() + " empty weekday(s): " + targetDays);

    if (rooms.isEmpty() || personIds.size() < MIN_BOOKABLE_PEOPLE) {
      System.out.println(
          "Skipping: need at least one room and "
              + MIN_BOOKABLE_PEOPLE
              + " people to book a meeting, found "
              + rooms.size()
              + " room(s) and "
              + personIds.size()
              + " person(s). Re-run with the people and rooms concerns enabled first.");
      return new Summary(0, 0, 0, 0, 0);
    }

    final List<RoomInfo> roomInfos =
        rooms.stream().map(room -> new RoomInfo(room.id(), room.capacity())).toList();
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(roomInfos, personIds, targetDays, random);
    // Grouped by day, one bulk call per day, rather than one call per meeting. With a whole
    // calendar day stored as ONE DynamoDB item guarded by optimistic locking, concurrent creates
    // that land on the same date contend for the same item version - so the old
    // runInParallel(meetings, ...) had every meeting on a given day fighting the others for it.
    // createMeetings writes the whole day in a single item write instead. Days remain parallel:
    // different dates are different items and cannot contend.
    final Map<LocalDate, List<GeneratedMeeting>> byDay =
        meetings.stream()
            .collect(
                Collectors.groupingBy(
                    meeting -> meeting.startTime().toLocalDate(),
                    TreeMap::new,
                    Collectors.toList()));
    System.out.println(
        "Creating " + meetings.size() + " meeting(s) across " + byDay.size() + " day(s)...");
    runInParallel(
        List.copyOf(byDay.entrySet()),
        day -> createMeetingsForDay(client, day.getKey(), day.getValue()));

    System.out.println(
        "Done: "
            + targetDays.size()
            + " weekday(s) topped up, "
            + meetings.size()
            + " meeting(s) created.");
    return new Summary(0, 0, targetDays.size(), meetings.size(), 0);
  }

  // --- Guaranteed meetings --------------------------------------------------------------

  /** One existing meeting, reduced to what the guarantee needs to reason about. */
  record MeetingDetail(
      String roomId, String organiserId, List<String> attendeeIds, LocalTime start, LocalTime end) {

    boolean involves(final String personId) {
      return organiserId.equals(personId) || attendeeIds.contains(personId);
    }

    /** Touching end-to-start is not an overlap, matching the API's own rule. */
    boolean overlaps(final LocalTime otherStart, final LocalTime otherEnd) {
      return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
  }

  /** A room and a start time that is free in it. */
  record Slot(RoomDetail room, LocalTime start) {}

  /**
   * Gives every guaranteed person at least one meeting on every work day in the window.
   *
   * <p>Without this the demo account's calendar is populated only by luck: {@link MeetingScheduler}
   * picks organisers and attendees at random from every Person, so the account the signed-out home
   * page hands every visitor can show an empty calendar. {@code topUpMeetings} does not help - it
   * asks whether a day has ANY meeting, and the calendar is filtered to one person.
   *
   * <p>Idempotent like the other concerns: a day where the person already appears is skipped, so a
   * second run creates nothing.
   *
   * <p>Package-private so tests can exercise it directly.
   */
  static int guaranteeMeetings(
      final GraphQlClient client,
      final List<String> guaranteedPersonIds,
      final Targets targets,
      final Random random) {
    if (guaranteedPersonIds.isEmpty()) {
      System.out.println("No guaranteed persons configured for this environment - skipping.");
      return 0;
    }

    final LocalDate today = LocalDate.now();
    final List<LocalDate> workDays =
        weekdaysBetween(
            today.minusDays(targets.daysInPast()), today.plusDays((long) targets.weeksAhead() * 7));
    if (workDays.isEmpty()) {
      return 0;
    }

    final Map<LocalDate, List<MeetingDetail>> byDate = fetchMeetingDetails(client, workDays);
    final List<RoomDetail> rooms = fetchRooms(client);
    final List<String> personIds = fetchPersonIds(client);
    if (rooms.isEmpty() || personIds.size() < MIN_BOOKABLE_PEOPLE) {
      System.out.println(
          "Skipping guaranteed meetings: need at least one room and "
              + MIN_BOOKABLE_PEOPLE
              + " people, found "
              + rooms.size()
              + " and "
              + personIds.size()
              + ".");
      return 0;
    }

    int created = 0;
    for (final String personId : guaranteedPersonIds) {
      final List<LocalDate> missing = daysMissingPerson(byDate, workDays, personId);
      if (missing.isEmpty()) {
        System.out.println("Person " + personId + " already has a meeting on every work day.");
        continue;
      }
      System.out.println(
          "Person " + personId + " is missing meetings on " + missing.size() + " work day(s).");

      for (final LocalDate day : missing) {
        final List<MeetingDetail> existing = byDate.getOrDefault(day, List.of());
        final Optional<Slot> slot = pickFreeSlot(rooms, existing);
        if (slot.isEmpty()) {
          // Every room is booked across every candidate hour. Placing a meeting anyway risks
          // TimeRangeUnavailable, so the day is skipped loudly rather than the run failing - a
          // demo calendar with one thin day is a far smaller problem than a failed release.
          System.out.println("  " + day + ": no free slot in any room, skipping.");
          continue;
        }
        final GeneratedMeeting meeting =
            guaranteedMeetingFor(personId, slot.get(), personIds, day, random);
        createMeetingsForDay(client, day, List.of(meeting));
        created++;
        // Record it so a second guaranteed person does not pick the same slot for the same day.
        byDate
            .computeIfAbsent(day, ignored -> new ArrayList<>())
            .add(
                new MeetingDetail(
                    slot.get().room().id(),
                    personId,
                    meeting.attendeeIds(),
                    slot.get().start(),
                    slot.get().start().plusMinutes(GUARANTEED_MEETING_MINUTES)));
      }
    }
    System.out.println("Guaranteed meetings created: " + created + ".");
    return created;
  }

  /**
   * Work days on which {@code personId} is neither organiser nor attendee of any meeting. Pure, so
   * the guarantee's core decision is testable without AWS or a deployed environment.
   */
  static List<LocalDate> daysMissingPerson(
      final Map<LocalDate, List<MeetingDetail>> byDate,
      final List<LocalDate> workDays,
      final String personId) {
    return workDays.stream()
        .filter(
            day ->
                byDate.getOrDefault(day, List.of()).stream()
                    .noneMatch(meeting -> meeting.involves(personId)))
        .toList();
  }

  /**
   * A room and start time with nothing booked over it that day.
   *
   * <p>Searches SLOTS rather than whole free rooms. Requiring a room with no bookings at all was
   * the first attempt and it failed precisely where it was needed: a day busy enough to leave a
   * person uncovered is also a day with every room touched at least once, so the concern skipped
   * exactly the days it existed for. Measured on a seeded environment - the one uncovered day had
   * all ten rooms in use and nothing was created.
   *
   * <p>Pure, so the search is testable without AWS.
   */
  static Optional<Slot> pickFreeSlot(
      final List<RoomDetail> rooms, final List<MeetingDetail> dayMeetings) {
    for (final RoomDetail room : rooms) {
      if (room.capacity() < MIN_BOOKABLE_PEOPLE) {
        continue;
      }
      final List<MeetingDetail> inRoom =
          dayMeetings.stream().filter(m -> m.roomId().equals(room.id())).toList();
      for (int hour = GUARANTEED_MEETING_EARLIEST_HOUR;
          hour <= GUARANTEED_MEETING_LATEST_HOUR;
          hour++) {
        final LocalTime start = LocalTime.of(hour, 0);
        final LocalTime end = start.plusMinutes(GUARANTEED_MEETING_MINUTES);
        if (inRoom.stream().noneMatch(m -> m.overlaps(start, end))) {
          return Optional.of(new Slot(room, start));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * A single meeting with {@code personId} as organiser and one other person as attendee.
   *
   * <p>Times are kept simple because the room is free for the whole day: a half-hour on the hour
   * inside business hours cannot collide with anything. The organiser is never also listed as an
   * attendee - the API rejects that with {@code OrganiserIsAttendee}.
   */
  private static GeneratedMeeting guaranteedMeetingFor(
      final String personId,
      final Slot slot,
      final List<String> personIds,
      final LocalDate day,
      final Random random) {
    final List<String> others = personIds.stream().filter(id -> !id.equals(personId)).toList();
    final String attendee = others.get(random.nextInt(others.size()));
    final LocalDateTime start = day.atTime(slot.start());
    return new GeneratedMeeting(
        slot.room().id(),
        "Demo catch-up",
        personId,
        List.of(attendee),
        start,
        start.plusMinutes(GUARANTEED_MEETING_MINUTES));
  }

  /**
   * Every Monday-Friday date from {@code startInclusive} up to (but not including) {@code
   * endExclusive}. Package-private (rather than private) so tests can exercise it directly.
   */
  static List<LocalDate> weekdaysBetween(
      final LocalDate startInclusive, final LocalDate endExclusive) {
    final List<LocalDate> weekdays = new ArrayList<>();
    for (LocalDate day = startInclusive; day.isBefore(endExclusive); day = day.plusDays(1)) {
      final DayOfWeek dayOfWeek = day.getDayOfWeek();
      if (dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) {
        weekdays.add(day);
      }
    }
    return weekdays;
  }

  // --- Reads ----------------------------------------------------------------------------

  /**
   * A room as this tool needs it: capacity for scheduling, name so a top-up doesn't duplicate one.
   */
  record RoomDetail(String id, String name, int capacity) {}

  private static List<RoomDetail> fetchRooms(final GraphQlClient client) {
    final JsonNode result = client.execute("query { workspace { rooms { id name capacity } } }");
    final List<RoomDetail> rooms = new ArrayList<>();
    for (final JsonNode room : result.get("workspace").get("rooms")) {
      rooms.add(
          new RoomDetail(
              room.get("id").asText(), room.get("name").asText(), room.get("capacity").asInt()));
    }
    return rooms;
  }

  private static List<String> fetchPersonIds(final GraphQlClient client) {
    final JsonNode result = client.execute("query { workspace { people { id } } }");
    final List<String> personIds = new ArrayList<>();
    for (final JsonNode person : result.get("workspace").get("people")) {
      personIds.add(person.get("id").asText());
    }
    return personIds;
  }

  /**
   * The calendar dates (within the window) that already have at least one meeting, in any room.
   *
   * <p>Asks for the days by date and reads back which of them are non-empty, rather than fetching
   * every meeting in a time range and deriving the dates from their start times. The day-keyed
   * schema answers this directly: a date IS the key, so `id` is selected purely as the cheapest
   * non-empty marker and no meeting field is actually used.
   */
  private static Set<LocalDate> fetchDatesWithMeetings(
      final GraphQlClient client, final LocalDate windowStart, final LocalDate windowEnd) {
    final String query =
        "query DatesWithMeetings($dates: [String!]) { "
            + "workspace(dates: $dates) { days { date meetings { id } } } }";
    final List<String> dates =
        weekdaysBetween(windowStart, windowEnd).stream().map(LocalDate::toString).toList();
    if (dates.isEmpty()) {
      return Set.of();
    }

    final JsonNode result = client.execute(query, Map.of("dates", dates));
    final Set<LocalDate> withMeetings = new HashSet<>();
    for (final JsonNode day : result.get("workspace").get("days")) {
      if (!day.get("meetings").isEmpty()) {
        withMeetings.add(LocalDate.parse(day.get("date").asText()));
      }
    }
    return withMeetings;
  }

  /**
   * Existing meetings for the given days, with just the room and people fields the guarantee needs.
   *
   * <p>Separate from {@link #fetchDatesWithMeetings} rather than replacing it: that one answers
   * "does this day have any meeting at all", is asked for every top-up run, and selecting organiser
   * and attendees there would make every run pay for fields it does not use.
   */
  private static Map<LocalDate, List<MeetingDetail>> fetchMeetingDetails(
      final GraphQlClient client, final List<LocalDate> days) {
    final String query =
        "query MeetingDetails($dates: [String!]) { "
            + "workspace(dates: $dates) { days { date meetings { "
            + "room { id } organiser { id } attendees { id } startTime endTime } } } }";
    final List<String> dates = days.stream().map(LocalDate::toString).toList();
    if (dates.isEmpty()) {
      return new HashMap<>();
    }

    final JsonNode result = client.execute(query, Map.of("dates", dates));
    final Map<LocalDate, List<MeetingDetail>> byDate = new HashMap<>();
    for (final JsonNode day : result.get("workspace").get("days")) {
      final LocalDate date = LocalDate.parse(day.get("date").asText());
      final List<MeetingDetail> meetings = new ArrayList<>();
      for (final JsonNode meeting : day.get("meetings")) {
        final List<String> attendees = new ArrayList<>();
        for (final JsonNode attendee : meeting.get("attendees")) {
          attendees.add(attendee.get("id").asText());
        }
        meetings.add(
            new MeetingDetail(
                meeting.get("room").get("id").asText(),
                meeting.get("organiser").get("id").asText(),
                attendees,
                LocalDateTime.parse(meeting.get("startTime").asText()).toLocalTime(),
                LocalDateTime.parse(meeting.get("endTime").asText()).toLocalTime()));
      }
      byDate.put(date, meetings);
    }
    return byDate;
  }

  // --- Writes ---------------------------------------------------------------------------

  /**
   * Creates one day's meetings, splitting into as many calls as the API's per-call cap requires.
   *
   * <p>Almost always exactly one call: the scheduler generates a handful of meetings per day, well
   * under the cap. The chunking exists so that a future scheduler change cannot silently start
   * failing every seeded day at the ninety-ninth meeting.
   */
  private static void createMeetingsForDay(
      final GraphQlClient client, final LocalDate date, final List<GeneratedMeeting> meetings) {
    for (int from = 0; from < meetings.size(); from += MAX_MEETINGS_PER_BULK_CREATE) {
      final int to = Math.min(from + MAX_MEETINGS_PER_BULK_CREATE, meetings.size());
      createMeetingBatch(client, date, meetings.subList(from, to));
    }
  }

  private static void createMeetingBatch(
      final GraphQlClient client, final LocalDate date, final List<GeneratedMeeting> meetings) {
    final String mutation =
        "mutation CreateMeetings($date: String!, $meetings: [MeetingInput!]!) { "
            + "createMeetings(date: $date, meetings: $meetings) { failures { index errors } } }";
    final List<Map<String, Object>> inputs = meetings.stream().map(DemoData::meetingInput).toList();

    final JsonNode result =
        client.execute(mutation, Map.of("date", date.toString(), "meetings", inputs));
    failIfAnyRejected(result.get("createMeetings"), meetings);
    for (final GeneratedMeeting meeting : meetings) {
      System.out.println(
          "  "
              + meeting.subject()
              + " - "
              + meeting.startTime().format(MEETING_TIME_FORMAT)
              + " to "
              + meeting.endTime().format(MEETING_TIME_FORMAT));
    }
  }

  private static Map<String, Object> meetingInput(final GeneratedMeeting meeting) {
    final Map<String, Object> input = new HashMap<>();
    input.put("roomId", meeting.roomId());
    input.put("organiserId", meeting.organiserId());
    input.put("attendeeIds", meeting.attendeeIds());
    input.put("subject", meeting.subject());
    input.put("startTime", meeting.startTime().format(MEETING_TIME_FORMAT));
    input.put("endTime", meeting.endTime().format(MEETING_TIME_FORMAT));
    return input;
  }

  /**
   * Bulk creation reports rejections per input rather than failing the call, so a partial success
   * has to be turned back into a hard failure here - this tool has no way to recover from one, and
   * a silently short day is worse than a stopped run.
   *
   * <p>The failure names the SUBJECT, not just the index: an index into a request array the caller
   * can no longer see is not a diagnosis.
   */
  private static void failIfAnyRejected(
      final JsonNode payload, final List<GeneratedMeeting> meetings) {
    final JsonNode failures = payload.get("failures");
    if (failures == null || failures.isEmpty()) {
      return;
    }
    final List<String> described = new ArrayList<>();
    for (final JsonNode failure : failures) {
      final int index = failure.get("index").asInt();
      final String subject =
          index >= 0 && index < meetings.size()
              ? meetings.get(index).subject()
              : "meeting at index " + index;
      described.add(subject + " " + failure.get("errors"));
    }
    throw new IllegalStateException(
        "createMeetings rejected "
            + described.size()
            + " of "
            + meetings.size()
            + " meeting(s): "
            + String.join("; ", described));
  }

  private static void failIfErrors(final JsonNode payload, final String operationDescription) {
    final JsonNode errors = payload.get("errors");
    if (errors != null && !errors.isEmpty()) {
      throw new IllegalStateException(operationDescription + " was rejected: " + errors);
    }
  }

  // --- Concurrency ----------------------------------------------------------------------

  private static <T> T getResult(final Future<T> future) {
    try {
      return future.get();
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      throw cause instanceof RuntimeException runtimeException
          ? runtimeException
          : new IllegalStateException(cause);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while fetching existing rooms/people/meetings", e);
    }
  }

  /**
   * Runs {@code action} for every item, on a bounded pool of {@value #MAX_CONCURRENT_REQUESTS}
   * threads, and waits for them all to finish. The first failure is rethrown after every task has
   * completed, same as a sequential loop would have failed on the first bad item - just not
   * necessarily the same item, since order isn't guaranteed under parallel execution.
   * Package-private (rather than private) so {@code DemoDataConcurrencyTest} can exercise it
   * directly.
   */
  static <T> void runInParallel(final List<T> items, final Consumer<T> action) {
    if (items.isEmpty()) {
      return;
    }
    final ExecutorService executor =
        Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, items.size()));
    try {
      final List<Future<?>> futures = new ArrayList<>(items.size());
      for (final T item : items) {
        futures.add(executor.submit(() -> action.accept(item)));
      }
      RuntimeException firstFailure = null;
      for (final Future<?> future : futures) {
        try {
          future.get();
        } catch (final ExecutionException e) {
          if (firstFailure == null) {
            final Throwable cause = e.getCause();
            firstFailure =
                cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException(cause);
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted while waiting for parallel tasks to finish", e);
        }
      }
      if (firstFailure != null) {
        throw firstFailure;
      }
    } finally {
      executor.shutdown();
    }
  }
}
