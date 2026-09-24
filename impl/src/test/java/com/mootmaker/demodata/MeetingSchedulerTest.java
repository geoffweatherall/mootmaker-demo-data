package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.demodata.MeetingScheduler.GeneratedMeeting;
import com.mootmaker.demodata.MeetingScheduler.RoomInfo;
import org.junit.jupiter.api.Test;

/**
 * Covers the scheduling invariants of the single, merged MeetingSchedulerTest - the only real
 * difference under test is that this MeetingScheduler takes an explicit list of target dates rather
 * than a day-offset range (see its own doc comment for why), so these tests build that list with
 * {@link #businessDays}, itself built on {@link DemoData#weekdaysBetween} (covered separately by
 * DemoDataTest) rather than duplicating weekend-skipping logic here.
 */
class MeetingSchedulerTest {

  private static final int ROOM_COUNT = 10;
  private static final int PERSON_COUNT = 40;

  /** A handful of business days, forward-only - enough for the basic invariant tests below. */
  private static final int SHORT_RANGE_END_DAY_OFFSET = 14;

  /**
   * A wide enough range to get a statistically meaningful sample for the ratio-based tests below.
   */
  private static final int WIDE_RANGE_END_DAY_OFFSET = 55;

  private static final LocalTime BUSINESS_DAY_START = LocalTime.of(8, 0);
  private static final LocalTime BUSINESS_DAY_END = LocalTime.of(17, 0);

  /**
   * Weekdays from {@code today + startDayOffset} through {@code today + endDayOffsetInclusive},
   * inclusive.
   */
  private static List<LocalDate> businessDays(
      final int startDayOffset, final int endDayOffsetInclusive) {
    final LocalDate today = LocalDate.now();
    return DemoData.weekdaysBetween(
        today.plusDays(startDayOffset), today.plusDays(endDayOffsetInclusive + 1L));
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

  private static Map<String, List<GeneratedMeeting>> groupByParticipant(
      final List<GeneratedMeeting> meetings) {
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
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(tenRooms(), personIds(), days, new Random(1));

    assertTrue(
        meetings.size() >= ROOM_COUNT,
        "Expected at least one meeting per room, got " + meetings.size());
    // MAX_MEETINGS_PER_ROOM_PER_DAY (10) is a real safety cap in MeetingScheduler, not a guess -
    // exceeding it would be a defect, not just an unlikely sample.
    assertTrue(
        meetings.size() <= ROOM_COUNT * days.size() * 10,
        "Got implausibly many meetings: " + meetings.size());
  }

  @Test
  void neverOverlapsTwoMeetingsInTheSameRoom() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(2));

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

  /**
   * Per designs/realistic-demo-meeting-schedule.md: an attendee can be invited into up to 2
   * *simultaneous* overlapping meetings, never more - measured as true point-in-time depth (a
   * sweep-line check at each meeting's own start instant), not merely "does some other meeting
   * touch this one somewhere in its span". A wide meeting can legitimately be touched by two
   * separate, mutually non-overlapping shorter meetings without ever having 3 active at once.
   */
  @Test
  void attendeeNeverExceedsTheConcurrentInviteCap() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(7));

    final Map<String, List<GeneratedMeeting>> attendeeMeetingsByPerson = new HashMap<>();
    for (final GeneratedMeeting meeting : meetings) {
      for (final String attendeeId : meeting.attendeeIds()) {
        attendeeMeetingsByPerson.computeIfAbsent(attendeeId, _ -> new ArrayList<>()).add(meeting);
      }
    }

    for (final List<GeneratedMeeting> attendeeMeetings : attendeeMeetingsByPerson.values()) {
      for (final GeneratedMeeting probe : attendeeMeetings) {
        final long depthAtProbeStart =
            attendeeMeetings.stream()
                .filter(
                    m ->
                        !m.startTime().isAfter(probe.startTime())
                            && m.endTime().isAfter(probe.startTime()))
                .count();
        assertTrue(
            depthAtProbeStart <= 2,
            "Attendee has " + depthAtProbeStart + " simultaneous invites at " + probe.startTime());
      }
    }
  }

  /**
   * Per designs/realistic-demo-meeting-schedule.md: an organiser is never double-booked, as
   * organiser or attendee, at an overlapping time. Checked against only the meetings that person
   * actually organises, not every meeting they happen to attend elsewhere - an attendee may
   * legitimately be double-booked (see above), so a person's *other* attendee invites are not part
   * of this invariant.
   */
  @Test
  void organiserIsNeverDoubleBooked() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(14));

    for (final GeneratedMeeting organised : meetings) {
      for (final GeneratedMeeting other : meetings) {
        if (other == organised) {
          continue;
        }
        final boolean otherInvolvesOrganiser =
            other.organiserId().equals(organised.organiserId())
                || other.attendeeIds().contains(organised.organiserId());
        if (otherInvolvesOrganiser) {
          assertFalse(
              overlaps(organised, other),
              "Organiser "
                  + organised.organiserId()
                  + " is involved in overlapping meetings "
                  + organised
                  + " and "
                  + other);
        }
      }
    }
  }

  @Test
  void someMeetingsInDifferentRoomsOverlapInTime() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(2));

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
    assertTrue(
        foundCrossRoomOverlap,
        "Expected at least one pair of meetings in different rooms to overlap in time");
  }

  @Test
  void everyMeetingStartsAndEndsOnAFiveMinuteBoundary() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(3));

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
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(8));

    for (final GeneratedMeeting meeting : meetings) {
      assertFalse(
          meeting.startTime().toLocalTime().isBefore(BUSINESS_DAY_START),
          "Meeting " + meeting + " starts before business hours");
      assertFalse(
          meeting.endTime().toLocalTime().isAfter(BUSINESS_DAY_END),
          "Meeting " + meeting + " ends after business hours");
    }
  }

  @Test
  void durationsVary() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(4));

    final Set<Long> distinctDurationsMinutes =
        meetings.stream()
            .map(b -> Duration.between(b.startTime(), b.endTime()).toMinutes())
            .collect(Collectors.toSet());

    assertTrue(
        distinctDurationsMinutes.size() > 1,
        "Expected varied meeting lengths, got only " + distinctDurationsMinutes);
  }

  @Test
  void everyMeetingRespectsItsRoomCapacity() {
    final List<RoomInfo> rooms = tenRooms();
    final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            rooms, personIds(), businessDays(1, SHORT_RANGE_END_DAY_OFFSET), new Random(5));

    for (final GeneratedMeeting meeting : meetings) {
      final int totalPeople = 1 + meeting.attendeeIds().size();
      assertTrue(
          totalPeople <= capacityByRoomId.get(meeting.roomId()),
          "Meeting "
              + meeting
              + " exceeds room capacity "
              + capacityByRoomId.get(meeting.roomId()));
    }
  }

  @Test
  void everyMeetingHasAtLeastOneAttendeeBesidesTheOrganiser() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(9));

    for (final GeneratedMeeting meeting : meetings) {
      assertTrue(
          meeting.attendeeIds().size() >= 1,
          "Meeting " + meeting + " has no attendees besides the organiser");
    }
  }

  /** Per the design: roughly 20% of meetings are sized near a room's full capacity. */
  @Test
  void roughlyAFifthOfMeetingsAreNearRoomCapacity() {
    final List<RoomInfo> rooms = tenRooms();
    final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            rooms, personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(21));
    assertTrue(meetings.size() > 100, "sample too small: " + meetings.size());

    final long nearCapacityMeetings =
        meetings.stream()
            .filter(
                b -> {
                  final int totalPeople = 1 + b.attendeeIds().size();
                  final int capacity = capacityByRoomId.get(b.roomId());
                  return totalPeople >= Math.ceil(capacity * 0.8);
                })
            .count();
    final double fraction = (double) nearCapacityMeetings / meetings.size();

    assertTrue(
        fraction > 0.1 && fraction < 0.35,
        "Expected roughly 20% of meetings near room capacity, got fraction " + fraction);
  }

  /** Attendee statuses land roughly on the configured 60/13.3/13.3/13.3 mix. */
  @Test
  void attendeeStatusesFollowTheConfiguredMix() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(5));

    final List<String> allStatuses =
        meetings.stream().flatMap(m -> m.attendeeStatuses().stream()).toList();
    assertTrue(allStatuses.size() > 100, "sample too small: " + allStatuses.size());

    final Map<String, Long> counts =
        allStatuses.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
    final double total = allStatuses.size();
    final double going = counts.getOrDefault("Going", 0L) / total;

    assertTrue(going > 0.45 && going < 0.75, "Going share " + going + " - counts: " + counts);
    for (final String other : List.of("NotGoing", "Maybe", "NoResponse")) {
      final double share = counts.getOrDefault(other, 0L) / total;
      assertTrue(share > 0.03 && share < 0.28, other + " share " + share + " - counts: " + counts);
    }
    assertEquals(
        allStatuses.size(),
        counts.values().stream().mapToLong(Long::longValue).sum(),
        "every attendee status must be one of the four known values");
  }

  @Test
  void atLeastHalfOfMeetingsForEachPersonAreFollowedByAGap() {
    final List<GeneratedMeeting> allMeetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(42));

    int followedByGap = 0;
    int totalWithNext = 0;
    for (final List<GeneratedMeeting> meetings : groupByParticipant(allMeetings).values()) {
      final List<GeneratedMeeting> sorted =
          meetings.stream().sorted(Comparator.comparing(GeneratedMeeting::startTime)).toList();
      for (int i = 0; i < sorted.size() - 1; i++) {
        // A genuinely overlapping consecutive pair (possible now for an attendee, up to the
        // concurrent-invite cap) isn't a "gap or not" question - skip it rather than counting it
        // against the ratio.
        if (overlaps(sorted.get(i), sorted.get(i + 1))) {
          continue;
        }
        totalWithNext++;
        if (sorted.get(i).endTime().isBefore(sorted.get(i + 1).startTime())) {
          followedByGap++;
        }
      }
    }

    assertTrue(
        totalWithNext > 50,
        "Expected a meaningful sample of consecutive meetings, got " + totalWithNext);
    final double gapRatio = (double) followedByGap / totalWithNext;
    assertTrue(
        gapRatio >= 0.5,
        "Expected at least 50% of meetings to be followed by a gap, got " + gapRatio);
  }

  @Test
  void meetingsAreSpreadAcrossTheDayNotBunchedAtTheStart() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(11));

    final long startingInFirstTwoHours =
        meetings.stream()
            .filter(b -> b.startTime().toLocalTime().isBefore(LocalTime.of(10, 0)))
            .count();
    final double fraction = (double) startingInFirstTwoHours / meetings.size();

    assertTrue(
        fraction < 0.5,
        "Expected fewer than half of meetings to start in the first two business hours, got "
            + fraction);
  }

  @Test
  void neverSchedulesOnAWeekend() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(13));

    for (final GeneratedMeeting meeting : meetings) {
      final DayOfWeek dayOfWeek = meeting.startTime().getDayOfWeek();
      assertFalse(
          dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY,
          "Meeting " + meeting + " falls on a weekend");
    }
  }

  @Test
  void onlySchedulesOnTheGivenDays() {
    final List<LocalDate> days = businessDays(0, WIDE_RANGE_END_DAY_OFFSET);
    final Set<LocalDate> allowedDays = new HashSet<>(days);

    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(tenRooms(), personIds(), days, new Random(6));

    for (final GeneratedMeeting meeting : meetings) {
      final LocalDate day = meeting.startTime().toLocalDate();
      assertTrue(
          allowedDays.contains(day),
          "Meeting " + meeting + " falls on a day that wasn't requested");
    }
  }

  @Test
  void generatesNothingForAnEmptyDayList() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(tenRooms(), personIds(), List.of(), new Random(6));

    assertTrue(meetings.isEmpty());
  }

  // --- designs/realistic-demo-meeting-schedule.md invariants ---------------------------

  /** The bimodal time-of-day curve: two daily peaks with a lunch trough between them. */
  @Test
  void meetingStartTimesShowTwoDailyPeaksWithALunchTrough() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(51));
    assertTrue(meetings.size() > 100, "sample too small: " + meetings.size());

    final Map<Integer, Long> countByStartHour =
        meetings.stream()
            .collect(Collectors.groupingBy(m -> m.startTime().getHour(), Collectors.counting()));

    final long morningPeak = countByStartHour.getOrDefault(10, 0L);
    final long afternoonPeak = countByStartHour.getOrDefault(14, 0L);
    final long lunchTrough = countByStartHour.getOrDefault(12, 0L);

    assertTrue(
        lunchTrough < morningPeak && lunchTrough < afternoonPeak,
        "Expected the 12:00 hour to be a trough between two peaks - counts: " + countByStartHour);
    // Still a real (non-zero) floor, not a hard cutoff - per Geoff's preference for an occasional
    // lunch meeting over never allowing one.
    assertTrue(
        lunchTrough > 0,
        "Expected a small non-zero chance of a lunch meeting, got zero - counts: "
            + countByStartHour);
  }

  /**
   * A room's occupancy tier is a fixed trait of the room (derived from its id), not re-rolled per
   * day - confirmed with Geoff. Generating two independent day ranges with different seeds should
   * still agree on which room is busiest.
   */
  @Test
  void aRoomsOccupancyTierIsConsistentAcrossDifferentDaysAndSeeds() {
    final List<RoomInfo> rooms = tenRooms();
    final List<LocalDate> allDays = businessDays(0, WIDE_RANGE_END_DAY_OFFSET);
    final int midpoint = allDays.size() / 2;

    final Map<String, Long> firstHalfMinutes =
        occupiedMinutesByRoom(
            MeetingScheduler.generate(
                rooms, personIds(), allDays.subList(0, midpoint), new Random(61)));
    final Map<String, Long> secondHalfMinutes =
        occupiedMinutesByRoom(
            MeetingScheduler.generate(
                rooms, personIds(), allDays.subList(midpoint, allDays.size()), new Random(62)));

    final String busiestInFirstHalf =
        firstHalfMinutes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElseThrow()
            .getKey();
    final double secondHalfAverage =
        secondHalfMinutes.values().stream().mapToLong(Long::longValue).average().orElseThrow();

    assertTrue(
        secondHalfMinutes.getOrDefault(busiestInFirstHalf, 0L) > secondHalfAverage,
        "Expected the room busiest in the first half to still be above-average in the second half"
            + " - first half: "
            + firstHalfMinutes
            + ", second half: "
            + secondHalfMinutes);
  }

  private static Map<String, Long> occupiedMinutesByRoom(final List<GeneratedMeeting> meetings) {
    final Map<String, Long> minutesByRoom = new HashMap<>();
    for (final GeneratedMeeting meeting : meetings) {
      minutesByRoom.merge(
          meeting.roomId(),
          Duration.between(meeting.startTime(), meeting.endTime()).toMinutes(),
          Long::sum);
    }
    return minutesByRoom;
  }

  /**
   * The 95/5 RSVP conflict-resolution rule: a genuinely overlapping pair of one person's attendee
   * invites mostly resolves to exactly one Going, occasionally (the deliberate mistake) to both.
   */
  @Test
  void overlappingAttendeeInvitesResolveMostlyCorrectlySometimesBothGoing() {
    final List<GeneratedMeeting> meetings =
        MeetingScheduler.generate(
            tenRooms(), personIds(), businessDays(0, WIDE_RANGE_END_DAY_OFFSET), new Random(44));

    final Map<String, List<GeneratedMeeting>> attendeeMeetingsByPerson = new HashMap<>();
    for (final GeneratedMeeting meeting : meetings) {
      for (final String attendeeId : meeting.attendeeIds()) {
        attendeeMeetingsByPerson.computeIfAbsent(attendeeId, _ -> new ArrayList<>()).add(meeting);
      }
    }

    int overlappingPairs = 0;
    int exactlyOneGoing = 0;
    int bothGoing = 0;
    for (final Map.Entry<String, List<GeneratedMeeting>> entry :
        attendeeMeetingsByPerson.entrySet()) {
      final String personId = entry.getKey();
      final List<GeneratedMeeting> personMeetings = entry.getValue();
      for (int i = 0; i < personMeetings.size(); i++) {
        for (int j = i + 1; j < personMeetings.size(); j++) {
          final GeneratedMeeting a = personMeetings.get(i);
          final GeneratedMeeting b = personMeetings.get(j);
          if (!overlaps(a, b)) {
            continue;
          }
          overlappingPairs++;
          final boolean firstGoing = "Going".equals(statusFor(a, personId));
          final boolean secondGoing = "Going".equals(statusFor(b, personId));
          if (firstGoing && secondGoing) {
            bothGoing++;
          } else if (firstGoing != secondGoing) {
            exactlyOneGoing++;
          }
        }
      }
    }

    assertTrue(overlappingPairs > 30, "sample too small: " + overlappingPairs);
    final double bothGoingFraction = (double) bothGoing / overlappingPairs;
    assertTrue(
        bothGoingFraction > 0.01 && bothGoingFraction < 0.15,
        "Expected roughly 5% of overlapping invite pairs to both resolve Going, got "
            + bothGoingFraction
            + " (both="
            + bothGoing
            + ", exactlyOne="
            + exactlyOneGoing
            + ", total="
            + overlappingPairs
            + ")");
    assertTrue(
        exactlyOneGoing > bothGoing,
        "Expected most overlapping pairs to resolve to exactly one Going");
  }

  private static String statusFor(final GeneratedMeeting meeting, final String personId) {
    return meeting.attendeeStatuses().get(meeting.attendeeIds().indexOf(personId));
  }
}
