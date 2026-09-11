package com.mootmaker.demodata.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Asserts the <b>invariants</b> generated demo data must satisfy, against a real deployed
 * environment - not exact values, which would just restate the implementation.
 *
 * <p>These are the rules {@code MeetingScheduler} claims to enforce, checked against data the API
 * itself accepted, which is what makes the suite meaningful: demo-data writes exclusively through
 * GraphQL, so anything it produced is by definition data the API's own validation allowed.
 *
 * <p>Resets the environment once, then seeds it with a single run - the full-window path, which on
 * a fresh environment is every business day in the window. That path used to be the rarely
 * exercised one; making seeding and topping up the same operation is what puts it under test on
 * every run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Generated demo data satisfies its scheduling invariants")
class GeneratedDataInvariantsAcceptanceIT {

  // The seeded window, named once rather than repeated: the day-keyed schema has no "all
  // meetings" query, so reads must ask for an explicit list of dates and every read here has to
  // agree with the range the invariants assert over. DAYS_IN_PAST behind today and WEEKS_AHEAD in
  // front, so a freshly-seeded environment has history rather than starting empty today.
  /**
   * The API's cap on how many dates one {@code workspace} call may ask for, mirrored rather than
   * imported - this suite depends on the published schema, which documents the number, not on
   * mootmaker-api's Java.
   */
  private static final int MAX_DATES_PER_REQUEST = 42;

  /**
   * The booking horizon, mirrored from the schema so {@link #serverToday()} can subtract it back
   * off {@code latestBookableDate}.
   */
  private static final int BOOKING_HORIZON_DAYS = 180;

  // Anchored on the SERVER's today, not this machine's. The seeder runs in Lambda, in UTC; this
  // suite runs on a workstation, which in New Zealand is up to 13 hours ahead. For part of every
  // day the two disagree about what "today" is, and the window then runs one day past what the
  // seeder was ever asked to fill - failing as "business days with no meetings: [<the last one>]",
  // which says nothing about clocks. Observed at 01:42 NZST, when the server was still on the
  // previous UTC date. Two authorities on one fact is the bug; ask the one that owns it.
  private LocalDate windowStart;
  private LocalDate windowEnd;

  private static final int BUSINESS_DAY_START_HOUR = 8;
  private static final int BUSINESS_DAY_END_HOUR = 17;

  private final GraphQlClient client = new GraphQlClient();

  private List<Meeting> meetings;
  private Map<String, Integer> roomCapacities;

  private record Meeting(
      String id,
      String roomId,
      String organiserId,
      List<String> attendeeIds,
      LocalDateTime start,
      LocalDateTime end) {

    boolean overlaps(final Meeting other) {
      return start.isBefore(other.end) && other.start.isBefore(end);
    }

    List<String> participantIds() {
      final List<String> participants = new ArrayList<>(attendeeIds);
      participants.add(organiserId);
      return participants;
    }
  }

  @BeforeAll
  void seedAndReadBack() {
    // demo-data has no reset path by design, so clearing first is mootmaker-api's job - the
    // same two deliberate steps a human performs by hand.
    final LocalDate today = serverToday();
    windowStart = today.minusDays(7);
    windowEnd = today.plusWeeks(6);

    DemoDataLambda.reset();

    final JsonNode summary = DemoDataLambda.run();
    assertTrue(
        summary.get("meetingsCreated").asInt() > 0,
        "seeding a freshly reset environment must create meetings, got: " + summary);
    // The guaranteed-meetings concern, proved against a real environment rather than a unit
    // test's in-memory map. mootmaker-api publishes this environment's demo Person id, and a
    // freshly reset environment gives that person nothing - so a zero here means the SSM
    // parameter, the GraphQL query shape or the booking validation is wrong, none of which a
    // unit test can see.
    assertTrue(
        summary.get("guaranteedMeetingsCreated").asInt() > 0,
        "seeding a freshly reset environment must create guaranteed meetings, got: " + summary);

    meetings = fetchMeetings();
    roomCapacities = fetchRoomCapacities();
  }

  @Test
  @DisplayName("every business day in the window has at least one meeting")
  void everyBusinessDayInTheWindowIsPopulated() {
    final Set<LocalDate> populated =
        meetings.stream().map(m -> m.start().toLocalDate()).collect(Collectors.toSet());

    // The window reaches DAYS_IN_PAST behind today as well as WEEKS_AHEAD in front, so a
    // freshly-seeded environment has history rather than starting empty today.
    final List<LocalDate> missing = new ArrayList<>();
    for (LocalDate day = windowStart; day.isBefore(windowEnd); day = day.plusDays(1)) {
      if (isWeekend(day)) {
        continue;
      }
      if (!populated.contains(day)) {
        missing.add(day);
      }
    }
    assertTrue(missing.isEmpty(), "business days with no meetings: " + missing);
  }

  @Test
  @DisplayName("no meeting falls on a Saturday or Sunday")
  void noMeetingsAtTheWeekend() {
    final List<Meeting> weekend =
        meetings.stream().filter(m -> isWeekend(m.start().toLocalDate())).toList();

    assertTrue(weekend.isEmpty(), "weekend meetings: " + describe(weekend));
  }

  @Test
  @DisplayName("no meeting falls outside business hours")
  void everyMeetingIsWithinBusinessHours() {
    final List<Meeting> outside =
        meetings.stream()
            .filter(
                m ->
                    m.start().getHour() < BUSINESS_DAY_START_HOUR
                        || m.end().toLocalTime().isAfter(LocalTime.of(BUSINESS_DAY_END_HOUR, 0))
                        || !m.start().toLocalDate().equals(m.end().toLocalDate()))
            .toList();

    assertTrue(
        outside.isEmpty(),
        "meetings outside "
            + BUSINESS_DAY_START_HOUR
            + ":00-"
            + BUSINESS_DAY_END_HOUR
            + ":00: "
            + describe(outside));
  }

  @Test
  @DisplayName("no room is ever double-booked")
  void noRoomIsDoubleBooked() {
    final List<String> clashes = new ArrayList<>();
    for (final List<Meeting> inRoom : groupBy(meetings, Meeting::roomId)) {
      for (int i = 0; i < inRoom.size(); i++) {
        for (int j = i + 1; j < inRoom.size(); j++) {
          if (inRoom.get(i).overlaps(inRoom.get(j))) {
            clashes.add(describe(inRoom.get(i)) + " overlaps " + describe(inRoom.get(j)));
          }
        }
      }
    }
    assertTrue(clashes.isEmpty(), "double-booked rooms: " + clashes);
  }

  @Test
  @DisplayName("nobody is in two meetings at once")
  void noPersonIsInTwoOverlappingMeetings() {
    final Map<String, List<Meeting>> byPerson = new HashMap<>();
    for (final Meeting meeting : meetings) {
      for (final String personId : meeting.participantIds()) {
        byPerson.computeIfAbsent(personId, id -> new ArrayList<>()).add(meeting);
      }
    }

    final List<String> clashes = new ArrayList<>();
    for (final Map.Entry<String, List<Meeting>> entry : byPerson.entrySet()) {
      final List<Meeting> theirs = entry.getValue();
      for (int i = 0; i < theirs.size(); i++) {
        for (int j = i + 1; j < theirs.size(); j++) {
          if (theirs.get(i).overlaps(theirs.get(j))) {
            clashes.add(
                entry.getKey()
                    + ": "
                    + describe(theirs.get(i))
                    + " overlaps "
                    + describe(theirs.get(j)));
          }
        }
      }
    }
    assertTrue(clashes.isEmpty(), "people double-booked: " + clashes);
  }

  @Test
  @DisplayName("no meeting exceeds its room's capacity")
  void noMeetingExceedsItsRoomCapacity() {
    final List<String> over =
        meetings.stream()
            .filter(
                m ->
                    m.participantIds().size()
                        > roomCapacities.getOrDefault(m.roomId(), Integer.MAX_VALUE))
            .map(
                m ->
                    describe(m)
                        + " has "
                        + m.participantIds().size()
                        + " participants in a room of "
                        + roomCapacities.get(m.roomId()))
            .toList();

    assertTrue(over.isEmpty(), "meetings over room capacity: " + over);
  }

  @Test
  @DisplayName("every meeting has an organiser and at least one attendee")
  void everyMeetingHasParticipants() {
    final List<Meeting> underpopulated =
        meetings.stream()
            .filter(
                m ->
                    m.organiserId() == null
                        || m.organiserId().isBlank()
                        || m.attendeeIds().isEmpty())
            .toList();

    assertTrue(
        underpopulated.isEmpty(),
        "meetings without an organiser and an attendee: " + describe(underpopulated));
  }

  @Test
  @DisplayName("the people and room targets are met exactly, not exceeded")
  void targetsAreMet() {
    assertEquals(40, fetchCount("people"), "people should be topped up to the configured target");
    assertEquals(10, fetchCount("rooms"), "rooms should be topped up to the configured target");
  }

  @Test
  @DisplayName("running a second time changes nothing")
  void aSecondRunIsANoOp() {
    // The idempotency guard, and the single assertion most likely to catch a regression in any
    // of the four concerns: every one of them is defined by doing nothing when already done.
    final JsonNode summary = DemoDataLambda.run();

    assertEquals(0, summary.get("peopleCreated").asInt(), "second run created people: " + summary);
    assertEquals(0, summary.get("roomsCreated").asInt(), "second run created rooms: " + summary);
    assertEquals(
        0, summary.get("meetingsCreated").asInt(), "second run created meetings: " + summary);
    // Counted separately from meetingsCreated, so without this line the guarantee could create a
    // meeting for every guaranteed person on every run and the assertion above would stay green.
    assertEquals(
        0,
        summary.get("guaranteedMeetingsCreated").asInt(),
        "second run created guaranteed meetings: " + summary);
    assertEquals(
        meetings.size(), fetchMeetings().size(), "the second run changed the stored meeting count");
  }

  @Test
  @DisplayName("a disabled concern does nothing")
  void aDisabledConcernIsSkipped() {
    // Proves the toggle actually reaches the concern - the mechanism that lets a misbehaving
    // concern be switched off without redeploying.
    final int before = fetchCount("rooms");

    final JsonNode summary = DemoDataLambda.run("{\"rooms\": false, \"meetings\": false}");

    assertEquals(0, summary.get("roomsCreated").asInt());
    assertEquals(0, summary.get("meetingsCreated").asInt());
    assertEquals(before, fetchCount("rooms"));
  }

  // --- Reads ----------------------------------------------------------------------------

  /**
   * Every meeting the seeder placed in the window, read a day at a time.
   *
   * <p>There is no {@code Query.meetings} to ask any more - the composite entry point replaced it,
   * and a day-keyed store has no "all meetings" to return without a date range anyway. The window
   * below is the same one {@link #everyBusinessDayInTheWindowIsPopulated} asserts over, widened by
   * a day at each end so that a seeder which overshoots is caught rather than silently trimmed by
   * the query itself.
   */
  private List<Meeting> fetchMeetings() {
    final List<String> dates = new ArrayList<>();
    for (LocalDate day = windowStart.minusDays(1);
        day.isBefore(windowEnd.plusDays(1));
        day = day.plusDays(1)) {
      dates.add(day.toString());
    }
    final List<Meeting> found = new ArrayList<>();
    // In chunks, because workspace(dates:) caps at MAX_DATES_PER_REQUEST and this window is
    // wider than that. Weekends are included rather than skipped: the seeder is supposed to
    // place nothing on them, and a read that only asked for weekdays would make the invariant
    // asserting exactly that pass without being able to fail.
    for (int from = 0; from < dates.size(); from += MAX_DATES_PER_REQUEST) {
      final List<String> chunk =
          dates.subList(from, Math.min(from + MAX_DATES_PER_REQUEST, dates.size()));
      collectMeetings(chunk, found);
    }
    return found;
  }

  private void collectMeetings(final List<String> dates, final List<Meeting> found) {
    final JsonNode data =
        client.execute(
            "query SeededMeetings($dates: [String!]) { workspace(dates: $dates) { days { meetings {"
                + " id startTime endTime room { id } organiser { id } attendees { id } } } } }",
            Map.of("dates", dates));
    for (final JsonNode day : data.get("workspace").get("days")) {
      for (final JsonNode meeting : day.get("meetings")) {
        final List<String> attendeeIds = new ArrayList<>();
        for (final JsonNode attendee : meeting.get("attendees")) {
          attendeeIds.add(attendee.get("id").asText());
        }
        found.add(
            new Meeting(
                meeting.get("id").asText(),
                meeting.get("room").get("id").asText(),
                meeting.get("organiser").get("id").asText(),
                attendeeIds,
                LocalDateTime.parse(meeting.get("startTime").asText()),
                LocalDateTime.parse(meeting.get("endTime").asText())));
      }
    }
  }

  private Map<String, Integer> fetchRoomCapacities() {
    final JsonNode data = client.execute("query { workspace { rooms { id capacity } } }");
    final Map<String, Integer> capacities = new HashMap<>();
    for (final JsonNode room : data.get("workspace").get("rooms")) {
      capacities.put(room.get("id").asText(), room.get("capacity").asInt());
    }
    return capacities;
  }

  /**
   * The server's own current date, derived from the boundary it publishes.
   *
   * <p>{@code latestBookableDate} is computed per request as the server's today plus the booking
   * horizon, so subtracting the horizon recovers the date this environment believes it is. The
   * alternative, {@code LocalDate.now()}, is this workstation's opinion - a different fact wearing
   * the same name.
   */
  private LocalDate serverToday() {
    final JsonNode data =
        client.execute("query { workspace { boundaries { latestBookableDate } } }");
    final String latestBookable =
        data.get("workspace").get("boundaries").get("latestBookableDate").asText();
    return LocalDate.parse(latestBookable).minusDays(BOOKING_HORIZON_DAYS);
  }

  private int fetchCount(final String collection) {
    return client
        .execute("query { workspace { " + collection + " { id } } }")
        .get("workspace")
        .get(collection)
        .size();
  }

  // --- Helpers --------------------------------------------------------------------------

  private static boolean isWeekend(final LocalDate day) {
    return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
  }

  private static Collection<List<Meeting>> groupBy(
      final List<Meeting> meetings, final java.util.function.Function<Meeting, String> key) {
    return meetings.stream().collect(Collectors.groupingBy(key)).values();
  }

  private static String describe(final Meeting meeting) {
    return meeting.start() + "-" + meeting.end().toLocalTime() + " (room " + meeting.roomId() + ")";
  }

  private static String describe(final List<Meeting> meetings) {
    return meetings.stream()
        .limit(10)
        .map(GeneratedDataInvariantsAcceptanceIT::describe)
        .toList()
        .toString();
  }
}
