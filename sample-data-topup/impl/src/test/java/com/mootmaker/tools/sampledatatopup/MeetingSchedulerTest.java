package com.mootmaker.tools.sampledatatopup;

import com.mootmaker.tools.sampledatatopup.MeetingScheduler.GeneratedMeeting;
import com.mootmaker.tools.sampledatatopup.MeetingScheduler.RoomInfo;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the same scheduling invariants as sample-data-generator's identical-in-spirit
 * MeetingSchedulerTest - the only real difference under test is that this MeetingScheduler takes
 * an explicit list of target dates rather than a day-offset range (see its own doc comment for
 * why), so these tests build that list with {@link #businessDays}, itself built on {@link
 * SampleDataTopUp#weekdaysBetween} (covered separately by SampleDataTopUpTest) rather than
 * duplicating weekend-skipping logic here.
 */
class MeetingSchedulerTest {

    private static final int ROOM_COUNT = 10;
    private static final int PERSON_COUNT = 40;

    /** A handful of business days, forward-only - enough for the basic invariant tests below. */
    private static final int SHORT_RANGE_END_DAY_OFFSET = 14;

    /** A wide enough range to get a statistically meaningful sample for the ratio-based tests below. */
    private static final int WIDE_RANGE_END_DAY_OFFSET = 55;

    private static final LocalTime BUSINESS_DAY_START = LocalTime.of(8, 0);
    private static final LocalTime BUSINESS_DAY_END = LocalTime.of(17, 0);

    /** Weekdays from {@code today + startDayOffset} through {@code today + endDayOffsetInclusive}, inclusive. */
    private static List<LocalDate> businessDays(final int startDayOffset, final int endDayOffsetInclusive) {
        final LocalDate today = LocalDate.now();
        return SampleDataTopUp.weekdaysBetween(today.plusDays(startDayOffset), today.plusDays(endDayOffsetInclusive + 1L));
    }

    private static List<RoomInfo> tenRooms() {
        final List<RoomInfo> rooms = new ArrayList<>();
        for (int i = 0; i < ROOM_COUNT; i++) {
            rooms.add(new RoomInfo("room-" + i, 2 + i));
        }
        return rooms;
    }

    private static List<String> personIds() {
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < PERSON_COUNT; i++) {
            ids.add("person-" + i);
        }
        return ids;
    }

    private static Map<String, Integer> capacityByRoomId(final List<RoomInfo> rooms) {
        final Map<String, Integer> capacityByRoomId = new HashMap<>();
        for (final RoomInfo room : rooms) {
            capacityByRoomId.put(room.id(), room.capacity());
        }
        return capacityByRoomId;
    }

    private static boolean overlaps(final GeneratedMeeting a, final GeneratedMeeting b) {
        return a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
    }

    private static Map<String, List<GeneratedMeeting>> groupByParticipant(final List<GeneratedMeeting> meetings) {
        final Map<String, List<GeneratedMeeting>> byParticipant = new HashMap<>();
        for (final GeneratedMeeting meeting : meetings) {
            byParticipant.computeIfAbsent(meeting.organiserId(), _ -> new ArrayList<>()).add(meeting);
            for (final String attendeeId : meeting.attendeeIds()) {
                byParticipant.computeIfAbsent(attendeeId, _ -> new ArrayList<>()).add(meeting);
            }
        }
        return byParticipant;
    }

    @Test
    void generatesAPlausibleNumberOfMeetings() {
        final List<LocalDate> days = businessDays(1, SHORT_RANGE_END_DAY_OFFSET);
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(), days, new Random(1));

        assertTrue(meetings.size() >= ROOM_COUNT, "Expected at least one meeting per room, got " + meetings.size());
        assertTrue(meetings.size() <= ROOM_COUNT * days.size() * 2, "Got implausibly many meetings: " + meetings.size());
    }

    @Test
    void neverOverlapsTwoMeetingsInTheSameRoom() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(2));

        final Map<String, List<GeneratedMeeting>> byRoom = new HashMap<>();
        for (final GeneratedMeeting meeting : meetings) {
            byRoom.computeIfAbsent(meeting.roomId(), _ -> new ArrayList<>()).add(meeting);
        }

        for (final List<GeneratedMeeting> roomMeetings : byRoom.values()) {
            for (int i = 0; i < roomMeetings.size(); i++) {
                for (int j = i + 1; j < roomMeetings.size(); j++) {
                    final GeneratedMeeting a = roomMeetings.get(i);
                    final GeneratedMeeting b = roomMeetings.get(j);
                    assertFalse(overlaps(a, b), "Meetings " + a + " and " + b + " overlap in the same room");
                }
            }
        }
    }

    @Test
    void neverDoubleBooksAPersonAcrossAnyRoom() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(7));

        for (final List<GeneratedMeeting> participantMeetings : groupByParticipant(meetings).values()) {
            for (int i = 0; i < participantMeetings.size(); i++) {
                for (int j = i + 1; j < participantMeetings.size(); j++) {
                    final GeneratedMeeting a = participantMeetings.get(i);
                    final GeneratedMeeting b = participantMeetings.get(j);
                    assertFalse(overlaps(a, b), "Participant is double-booked in " + a + " and " + b);
                }
            }
        }
    }

    @Test
    void someMeetingsInDifferentRoomsOverlapInTime() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(2));

        boolean foundCrossRoomOverlap = false;
        for (int i = 0; i < meetings.size() && !foundCrossRoomOverlap; i++) {
            for (int j = i + 1; j < meetings.size(); j++) {
                final GeneratedMeeting a = meetings.get(i);
                final GeneratedMeeting b = meetings.get(j);
                if (!a.roomId().equals(b.roomId()) && overlaps(a, b)) {
                    foundCrossRoomOverlap = true;
                    break;
                }
            }
        }
        assertTrue(foundCrossRoomOverlap, "Expected at least one pair of meetings in different rooms to overlap in time");
    }

    @Test
    void everyMeetingStartsAndEndsOnAFiveMinuteBoundary() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(3));

        for (final GeneratedMeeting meeting : meetings) {
            assertEquals(0, meeting.startTime().getSecond());
            assertEquals(0, meeting.startTime().getNano());
            assertEquals(0, meeting.startTime().getMinute() % 15);
            assertEquals(0, meeting.endTime().getSecond());
            assertEquals(0, meeting.endTime().getNano());
            assertEquals(0, meeting.endTime().getMinute() % 15);
        }
    }

    @Test
    void everyMeetingIsWithinBusinessHours() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(8));

        for (final GeneratedMeeting meeting : meetings) {
            assertFalse(meeting.startTime().toLocalTime().isBefore(BUSINESS_DAY_START),
                    "Meeting " + meeting + " starts before business hours");
            assertFalse(meeting.endTime().toLocalTime().isAfter(BUSINESS_DAY_END),
                    "Meeting " + meeting + " ends after business hours");
        }
    }

    @Test
    void durationsVary() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(4));

        final Set<Long> distinctDurationsMinutes = meetings.stream()
                .map(b -> Duration.between(b.startTime(), b.endTime()).toMinutes())
                .collect(Collectors.toSet());

        assertTrue(distinctDurationsMinutes.size() > 1, "Expected varied meeting lengths, got only " + distinctDurationsMinutes);
    }

    @Test
    void everyMeetingRespectsItsRoomCapacity() {
        final List<RoomInfo> rooms = tenRooms();
        final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(rooms, personIds(),
                businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(5));

        for (final GeneratedMeeting meeting : meetings) {
            final int totalPeople = 1 + meeting.attendeeIds().size();
            assertTrue(totalPeople <= capacityByRoomId.get(meeting.roomId()),
                    "Meeting " + meeting + " exceeds room capacity " + capacityByRoomId.get(meeting.roomId()));
        }
    }

    @Test
    void everyMeetingHasAtLeastOneAttendeeBesidesTheOrganiser() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(9));

        for (final GeneratedMeeting meeting : meetings) {
            assertTrue(meeting.attendeeIds().size() >= 1,
                    "Meeting " + meeting + " has no attendees besides the organiser");
        }
    }

    @Test
    void atLeastHalfOfMeetingsUseAtLeastHalfTheRoomCapacity() {
        final List<RoomInfo> rooms = tenRooms();
        final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(rooms, personIds(),
                businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(21));

        final long largeMeetings = meetings.stream()
                .filter(b -> {
                    final int totalPeople = 1 + b.attendeeIds().size();
                    final int capacity = capacityByRoomId.get(b.roomId());
                    return totalPeople >= Math.ceil(capacity / 2.0);
                })
                .count();
        final double fraction = (double) largeMeetings / meetings.size();

        assertTrue(fraction >= 0.5,
                "Expected at least half of meetings to use at least half the room's capacity, got fraction " + fraction);
    }

    @Test
    void atLeastHalfOfMeetingsForEachPersonAreFollowedByAGap() {
        final List<GeneratedMeeting> allMeetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(42));

        int followedByGap = 0;
        int totalWithNext = 0;
        for (final List<GeneratedMeeting> meetings : groupByParticipant(allMeetings).values()) {
            final List<GeneratedMeeting> sorted = meetings.stream()
                    .sorted(Comparator.comparing(GeneratedMeeting::startTime))
                    .toList();
            for (int i = 0; i < sorted.size() - 1; i++) {
                totalWithNext++;
                if (sorted.get(i).endTime().isBefore(sorted.get(i + 1).startTime())) {
                    followedByGap++;
                }
            }
        }

        assertTrue(totalWithNext > 50, "Expected a meaningful sample of consecutive meetings, got " + totalWithNext);
        final double gapRatio = (double) followedByGap / totalWithNext;
        assertTrue(gapRatio >= 0.5, "Expected at least 50% of meetings to be followed by a gap, got " + gapRatio);
    }

    @Test
    void meetingsAreSpreadAcrossTheDayNotBunchedAtTheStart() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(11));

        final long startingInFirstTwoHours = meetings.stream()
                .filter(b -> b.startTime().toLocalTime().isBefore(LocalTime.of(10, 0)))
                .count();
        final double fraction = (double) startingInFirstTwoHours / meetings.size();

        assertTrue(fraction < 0.5,
                "Expected fewer than half of meetings to start in the first two business hours, got " + fraction);
    }

    @Test
    void neverSchedulesOnAWeekend() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(),
                businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(13));

        for (final GeneratedMeeting meeting : meetings) {
            final DayOfWeek dayOfWeek = meeting.startTime().getDayOfWeek();
            assertFalse(dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY,
                    "Meeting " + meeting + " falls on a weekend");
        }
    }

    @Test
    void onlySchedulesOnTheGivenDays() {
        final List<LocalDate> days = businessDays(0, WIDE_RANGE_END_DAY_OFFSET);
        final Set<LocalDate> allowedDays = new HashSet<>(days);

        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(), days, new Random(6));

        for (final GeneratedMeeting meeting : meetings) {
            final LocalDate day = meeting.startTime().toLocalDate();
            assertTrue(allowedDays.contains(day), "Meeting " + meeting + " falls on a day that wasn't requested");
        }
    }

    @Test
    void generatesNothingForAnEmptyDayList() {
        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(tenRooms(), personIds(), List.of(), new Random(6));

        assertTrue(meetings.isEmpty());
    }
}
