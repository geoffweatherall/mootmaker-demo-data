package com.mootmaker.demodata.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;

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
   * The chunk size this suite reads meetings in. Well under the API's separate 42-dates-per-call
   * limit on the {@code workspace(dates:)} array itself (documented in the schema, not mirrored
   * here since nothing else in this suite needs that number) - the constraint that actually sizes
   * this constant is the API's cap on a single response's total meeting count ("The response would
   * be too large: more than 2000 meetings across the requested dates" - hit for real once room
   * occupancy rose under designs/realistic-demo-meeting-schedule.md). Five days keeps every chunk
   * comfortably under 2000 even at MeetingScheduler's own MAX_MEETINGS_PER_ROOM_PER_DAY safety
   * ceiling (10) across as many rooms as this tool is ever likely to manage.
   */
  private static final int MEETINGS_READ_CHUNK_SIZE = 5;

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
  private List<String> guaranteedPersonIds;

  private record Meeting(
      String id,
      String roomId,
      String organiserId,
      List<String> attendeeIds,
      List<String> attendeeStatuses,
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
    // guaranteedMeetingsCreated is deliberately NOT asserted > 0 here: topUpMeetings runs first
    // and randomly distributes meetings across every person, so it can itself give the
    // guaranteed person every work day purely by chance, leaving guaranteeMeetings with nothing
    // left to do - a correct, idempotent zero (see mootmaker-demo-data#32). What has to be true
    // regardless of how the RNG landed is checked below, against the read-back data, in
    // guaranteedPersonHasAMeetingEveryWorkDay.

    meetings = fetchMeetings();
    roomCapacities = fetchRoomCapacities();
    guaranteedPersonIds = fetchGuaranteedPersonIds();
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
  @DisplayName("every guaranteed person has a meeting on every work day in the window")
  void guaranteedPersonHasAMeetingEveryWorkDay() {
    // The guarantee's actual contract - see mootmaker-demo-data#32. guaranteeMeetings's own
    // created-count can legitimately be zero on a run where topUpMeetings' random placement
    // already covered every day for this person by chance, so that counter can't be what this
    // suite checks. This can: regardless of which of the two concerns is responsible for any
    // given day, the guaranteed person must show up as organiser or attendee on all of them.
    // Optional by design (see SsmSecrets#guaranteedPersonIds) - an environment whose
    // mootmaker-api predates the parameter has nothing configured to check here, and that is not
    // itself a failure.
    assumeTrue(
        !guaranteedPersonIds.isEmpty(),
        "no guaranteed person ids configured for this environment (see"
            + " mootmaker-api/deploy/terraform/demo-data-credentials.tf) - skipping");

    for (final String personId : guaranteedPersonIds) {
      final Set<LocalDate> daysWithPerson =
          meetings.stream()
              .filter(m -> m.participantIds().contains(personId))
              .map(m -> m.start().toLocalDate())
              .collect(Collectors.toSet());

      final List<LocalDate> missing = new ArrayList<>();
      for (LocalDate day = windowStart; day.isBefore(windowEnd); day = day.plusDays(1)) {
        if (isWeekend(day)) {
          continue;
        }
        if (!daysWithPerson.contains(day)) {
          missing.add(day);
        }
      }
      assertTrue(
          missing.isEmpty(), "guaranteed person " + personId + " has no meeting on: " + missing);
    }
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

  /**
   * Per designs/realistic-demo-meeting-schedule.md: an organiser is never double-booked, as
   * organiser or attendee, at an overlapping time - running two meetings at once isn't something an
   * RSVP can resolve. Checked only against meetings the person actually organises: an attendee may
   * legitimately be double-booked elsewhere (see {@link
   * #attendeeNeverExceedsTheConcurrentInviteCap} below), so that's not part of this invariant.
   */
  @Test
  @DisplayName("an organiser is never double-booked, as organiser or attendee")
  void organiserIsNeverDoubleBooked() {
    final List<String> clashes = new ArrayList<>();
    for (final Meeting organised : meetings) {
      for (final Meeting other : meetings) {
        if (other == organised) {
          continue;
        }
        final boolean otherInvolvesOrganiser =
            other.organiserId().equals(organised.organiserId())
                || other.attendeeIds().contains(organised.organiserId());
        if (otherInvolvesOrganiser && organised.overlaps(other)) {
          clashes.add(
              organised.organiserId()
                  + ": "
                  + describe(organised)
                  + " overlaps "
                  + describe(other));
        }
      }
    }
    assertTrue(clashes.isEmpty(), "organisers double-booked: " + clashes);
  }

  /**
   * Per designs/realistic-demo-meeting-schedule.md: an attendee can be invited into up to 2
   * *simultaneous* overlapping meetings, modelling a real conflicting invite that gets resolved via
   * RSVP rather than never existing. Measured as true point-in-time depth (a sweep-line check at
   * each meeting's own start instant) - a wide meeting can legitimately be touched by two separate,
   * mutually non-overlapping shorter meetings without ever having 3 active at once.
   */
  @Test
  @DisplayName("an attendee is never invited into more than 2 simultaneous meetings")
  void attendeeNeverExceedsTheConcurrentInviteCap() {
    final Map<String, List<Meeting>> attendeeMeetingsByPerson = new HashMap<>();
    for (final Meeting meeting : meetings) {
      for (final String attendeeId : meeting.attendeeIds()) {
        attendeeMeetingsByPerson.computeIfAbsent(attendeeId, id -> new ArrayList<>()).add(meeting);
      }
    }

    final List<String> overCap = new ArrayList<>();
    for (final Map.Entry<String, List<Meeting>> entry : attendeeMeetingsByPerson.entrySet()) {
      final List<Meeting> theirs = entry.getValue();
      for (final Meeting probe : theirs) {
        final long depthAtProbeStart =
            theirs.stream()
                .filter(m -> !m.start().isAfter(probe.start()) && m.end().isAfter(probe.start()))
                .count();
        if (depthAtProbeStart > 2) {
          overCap.add(
              entry.getKey()
                  + " has "
                  + depthAtProbeStart
                  + " simultaneous invites at "
                  + probe.start());
        }
      }
    }
    assertTrue(overCap.isEmpty(), "attendees over the concurrent-invite cap: " + overCap);
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
  @DisplayName("attendee status mix is roughly 60% Going, the rest split across the other three")
  void attendeeStatusMixIsRoughlyTheConfiguredSplit() {
    // A statistical check, not an exact one - see mootmaker-demo-data#32 for why an assertion
    // that depends on the RNG must be tolerant enough that only a real regression can fail it,
    // not this run's particular seed. The sample here is every generated attendee across the
    // whole seeded window (hundreds, per targetsAreMet's meeting counts), which is large enough
    // that a 15-point band around each target is many standard deviations wide - a genuinely
    // correct 60/13.3/13.3/13.3 generator essentially never misses it, while a generator that
    // regressed to (say) a uniform 25/25/25/25 split reliably would.
    final List<String> allStatuses =
        meetings.stream().flatMap(m -> m.attendeeStatuses().stream()).toList();
    assertTrue(
        allStatuses.size() > 100,
        "sample too small to say anything about the mix statistically: " + allStatuses.size());

    final Map<String, Long> counts =
        allStatuses.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
    final double total = allStatuses.size();
    final double going = counts.getOrDefault("Going", 0L) / total;
    final double notGoing = counts.getOrDefault("NotGoing", 0L) / total;
    final double maybe = counts.getOrDefault("Maybe", 0L) / total;
    final double noResponse = counts.getOrDefault("NoResponse", 0L) / total;

    assertTrue(
        going > 0.45 && going < 0.75,
        "Going share " + going + " is far from the ~60% target - counts: " + counts);
    for (final double share : List.of(notGoing, maybe, noResponse)) {
      assertTrue(
          share > 0.03 && share < 0.28,
          "a non-Going share is far from the ~13.3% target - counts: " + counts);
    }
  }

  @Test
  @DisplayName("the people and room targets are met exactly, not exceeded")
  void targetsAreMet() {
    assertEquals(100, fetchCount("people"), "people should be topped up to the configured target");
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
    // In chunks of MEETINGS_READ_CHUNK_SIZE (well under MAX_DATES_PER_REQUEST - see that
    // constant's own doc comment for the separate total-meeting-count cap this avoids). Weekends
    // are included rather than skipped: the seeder is supposed to place nothing on them, and a
    // read that only asked for weekdays would make the invariant asserting exactly that pass
    // without being able to fail.
    for (int from = 0; from < dates.size(); from += MEETINGS_READ_CHUNK_SIZE) {
      final List<String> chunk =
          dates.subList(from, Math.min(from + MEETINGS_READ_CHUNK_SIZE, dates.size()));
      collectMeetings(chunk, found);
    }
    return found;
  }

  private void collectMeetings(final List<String> dates, final List<Meeting> found) {
    final JsonNode data =
        client.execute(
            "query SeededMeetings($dates: [String!]) { workspace(dates: $dates) { days { meetings {"
                + " id startTime endTime room { id } organiser { id }"
                + " attendees { person { id } status } } } } }",
            Map.of("dates", dates));
    for (final JsonNode day : data.get("workspace").get("days")) {
      for (final JsonNode meeting : day.get("meetings")) {
        final List<String> attendeeIds = new ArrayList<>();
        final List<String> attendeeStatuses = new ArrayList<>();
        for (final JsonNode attendee : meeting.get("attendees")) {
          attendeeIds.add(attendee.get("person").get("id").asText());
          attendeeStatuses.add(attendee.get("status").asText());
        }
        found.add(
            new Meeting(
                meeting.get("id").asText(),
                meeting.get("room").get("id").asText(),
                meeting.get("organiser").get("id").asText(),
                attendeeIds,
                attendeeStatuses,
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
   * Person ids the guarantee must cover, read directly from SSM rather than through GraphQL - this
   * is a Person id, not something the schema exposes a query for. Written by mootmaker-api (see its
   * demo-data-credentials.tf), the same parameter {@code SsmSecrets.guaranteedPersonIds} reads
   * inside the Lambda itself. Empty, not an error, when the parameter does not exist - see that
   * method's own doc comment for why the guarantee is optional.
   */
  private List<String> fetchGuaranteedPersonIds() {
    final String name =
        "/mootmaker/" + System.getenv("ENVIRONMENT") + "/demo-data/guaranteed-person-ids";
    try (SsmClient ssm = SsmClient.create()) {
      final GetParametersResponse response =
          ssm.getParameters(GetParametersRequest.builder().names(name).build());
      if (response.parameters().isEmpty()) {
        return List.of();
      }
      final String value = response.parameters().getFirst().value();
      if (value == null || value.isBlank()) {
        return List.of();
      }
      return Arrays.stream(value.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
    }
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
