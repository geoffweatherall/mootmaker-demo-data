package com.mootmaker.demodata.verify;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final int BUSINESS_DAY_START_HOUR = 8;
    private static final int BUSINESS_DAY_END_HOUR = 17;

    private final GraphQlClient client = new GraphQlClient();

    private List<Meeting> meetings;
    private Map<String, Integer> roomCapacities;

    private record Meeting(String id, String roomId, String organiserId, List<String> attendeeIds,
            LocalDateTime start, LocalDateTime end) {

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
        DemoDataLambda.reset();

        final JsonNode summary = DemoDataLambda.run();
        assertTrue(summary.get("meetingsCreated").asInt() > 0,
                "seeding a freshly reset environment must create meetings, got: " + summary);

        meetings = fetchMeetings();
        roomCapacities = fetchRoomCapacities();
    }

    @Test
    @DisplayName("every business day in the window has at least one meeting")
    void everyBusinessDayInTheWindowIsPopulated() {
        final Set<LocalDate> populated = meetings.stream().map(m -> m.start().toLocalDate()).collect(Collectors.toSet());

        // The window reaches DAYS_IN_PAST behind today as well as WEEKS_AHEAD in front, so a
        // freshly-seeded environment has history rather than starting empty today.
        final List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day = LocalDate.now().minusDays(7); day.isBefore(LocalDate.now().plusWeeks(6)); day = day.plusDays(1)) {
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
        final List<Meeting> weekend = meetings.stream().filter(m -> isWeekend(m.start().toLocalDate())).toList();

        assertTrue(weekend.isEmpty(), "weekend meetings: " + describe(weekend));
    }

    @Test
    @DisplayName("no meeting falls outside business hours")
    void everyMeetingIsWithinBusinessHours() {
        final List<Meeting> outside = meetings.stream()
                .filter(m -> m.start().getHour() < BUSINESS_DAY_START_HOUR
                        || m.end().toLocalTime().isAfter(LocalTime.of(BUSINESS_DAY_END_HOUR, 0))
                        || !m.start().toLocalDate().equals(m.end().toLocalDate()))
                .toList();

        assertTrue(outside.isEmpty(), "meetings outside " + BUSINESS_DAY_START_HOUR + ":00-"
                + BUSINESS_DAY_END_HOUR + ":00: " + describe(outside));
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
                        clashes.add(entry.getKey() + ": " + describe(theirs.get(i)) + " overlaps " + describe(theirs.get(j)));
                    }
                }
            }
        }
        assertTrue(clashes.isEmpty(), "people double-booked: " + clashes);
    }

    @Test
    @DisplayName("no meeting exceeds its room's capacity")
    void noMeetingExceedsItsRoomCapacity() {
        final List<String> over = meetings.stream()
                .filter(m -> m.participantIds().size() > roomCapacities.getOrDefault(m.roomId(), Integer.MAX_VALUE))
                .map(m -> describe(m) + " has " + m.participantIds().size() + " participants in a room of "
                        + roomCapacities.get(m.roomId()))
                .toList();

        assertTrue(over.isEmpty(), "meetings over room capacity: " + over);
    }

    @Test
    @DisplayName("every meeting has an organiser and at least one attendee")
    void everyMeetingHasParticipants() {
        final List<Meeting> underpopulated = meetings.stream()
                .filter(m -> m.organiserId() == null || m.organiserId().isBlank() || m.attendeeIds().isEmpty())
                .toList();

        assertTrue(underpopulated.isEmpty(), "meetings without an organiser and an attendee: " + describe(underpopulated));
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
        // of the three concerns: every one of them is defined by doing nothing when already done.
        final JsonNode summary = DemoDataLambda.run();

        assertEquals(0, summary.get("peopleCreated").asInt(), "second run created people: " + summary);
        assertEquals(0, summary.get("roomsCreated").asInt(), "second run created rooms: " + summary);
        assertEquals(0, summary.get("meetingsCreated").asInt(), "second run created meetings: " + summary);
        assertEquals(meetings.size(), fetchMeetings().size(), "the second run changed the stored meeting count");
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

    private List<Meeting> fetchMeetings() {
        final JsonNode data = client.execute(
                "query { meetings { id startTime endTime room { id } organiser { id } attendees { id } } }");
        final List<Meeting> found = new ArrayList<>();
        for (final JsonNode meeting : data.get("meetings")) {
            final List<String> attendeeIds = new ArrayList<>();
            for (final JsonNode attendee : meeting.get("attendees")) {
                attendeeIds.add(attendee.get("id").asText());
            }
            found.add(new Meeting(
                    meeting.get("id").asText(),
                    meeting.get("room").get("id").asText(),
                    meeting.get("organiser").get("id").asText(),
                    attendeeIds,
                    LocalDateTime.parse(meeting.get("startTime").asText()),
                    LocalDateTime.parse(meeting.get("endTime").asText())));
        }
        return found;
    }

    private Map<String, Integer> fetchRoomCapacities() {
        final JsonNode data = client.execute("query { rooms { id capacity } }");
        final Map<String, Integer> capacities = new HashMap<>();
        for (final JsonNode room : data.get("rooms")) {
            capacities.put(room.get("id").asText(), room.get("capacity").asInt());
        }
        return capacities;
    }

    private int fetchCount(final String collection) {
        return client.execute("query { " + collection + " { id } }").get(collection).size();
    }

    // --- Helpers --------------------------------------------------------------------------

    private static boolean isWeekend(final LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static Collection<List<Meeting>> groupBy(final List<Meeting> meetings,
            final java.util.function.Function<Meeting, String> key) {
        return meetings.stream().collect(Collectors.groupingBy(key)).values();
    }

    private static String describe(final Meeting meeting) {
        return meeting.start() + "-" + meeting.end().toLocalTime() + " (room " + meeting.roomId() + ")";
    }

    private static String describe(final List<Meeting> meetings) {
        return meetings.stream().limit(10).map(GeneratedDataInvariantsAcceptanceIT::describe).toList().toString();
    }
}
