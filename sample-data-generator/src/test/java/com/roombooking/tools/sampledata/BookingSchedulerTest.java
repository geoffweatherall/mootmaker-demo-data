package com.roombooking.tools.sampledata;

import com.roombooking.tools.sampledata.BookingScheduler.GeneratedBooking;
import com.roombooking.tools.sampledata.BookingScheduler.RoomInfo;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingSchedulerTest {

    private static final int ROOM_COUNT = 10;
    private static final int PERSON_COUNT = 40;

    /** A handful of business days, forward-only - enough for the basic invariant tests below. */
    private static final int SHORT_RANGE_END_DAY_OFFSET = 14;

    /** The full range the real generator uses: a week in the past to seven weeks in the future. */
    private static final int FULL_RANGE_START_DAY_OFFSET = -7;
    private static final int FULL_RANGE_END_DAY_OFFSET = 49;

    private static final LocalTime BUSINESS_DAY_START = LocalTime.of(8, 0);
    private static final LocalTime BUSINESS_DAY_END = LocalTime.of(17, 0);

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

    private static boolean overlaps(final GeneratedBooking a, final GeneratedBooking b) {
        return a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
    }

    private static Map<String, List<GeneratedBooking>> groupByParticipant(final List<GeneratedBooking> bookings) {
        final Map<String, List<GeneratedBooking>> byParticipant = new HashMap<>();
        for (final GeneratedBooking booking : bookings) {
            byParticipant.computeIfAbsent(booking.organiserId(), _ -> new ArrayList<>()).add(booking);
            for (final String attendeeId : booking.attendeeIds()) {
                byParticipant.computeIfAbsent(attendeeId, _ -> new ArrayList<>()).add(booking);
            }
        }
        return byParticipant;
    }

    @Test
    void generatesAPlausibleNumberOfBookings() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(1));

        assertTrue(bookings.size() >= ROOM_COUNT, "Expected at least one booking per room, got " + bookings.size());
        assertTrue(bookings.size() <= ROOM_COUNT * SHORT_RANGE_END_DAY_OFFSET * 2,
                "Got implausibly many bookings: " + bookings.size());
    }

    @Test
    void neverOverlapsTwoBookingsInTheSameRoom() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(2));

        final Map<String, List<GeneratedBooking>> byRoom = new HashMap<>();
        for (final GeneratedBooking booking : bookings) {
            byRoom.computeIfAbsent(booking.roomId(), _ -> new ArrayList<>()).add(booking);
        }

        for (final List<GeneratedBooking> roomBookings : byRoom.values()) {
            for (int i = 0; i < roomBookings.size(); i++) {
                for (int j = i + 1; j < roomBookings.size(); j++) {
                    final GeneratedBooking a = roomBookings.get(i);
                    final GeneratedBooking b = roomBookings.get(j);
                    assertFalse(overlaps(a, b), "Bookings " + a + " and " + b + " overlap in the same room");
                }
            }
        }
    }

    @Test
    void neverDoubleBooksAPersonAcrossAnyRoom() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(7));

        for (final List<GeneratedBooking> participantBookings : groupByParticipant(bookings).values()) {
            for (int i = 0; i < participantBookings.size(); i++) {
                for (int j = i + 1; j < participantBookings.size(); j++) {
                    final GeneratedBooking a = participantBookings.get(i);
                    final GeneratedBooking b = participantBookings.get(j);
                    assertFalse(overlaps(a, b), "Participant is double-booked in " + a + " and " + b);
                }
            }
        }
    }

    @Test
    void someMeetingsInDifferentRoomsOverlapInTime() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(2));

        boolean foundCrossRoomOverlap = false;
        for (int i = 0; i < bookings.size() && !foundCrossRoomOverlap; i++) {
            for (int j = i + 1; j < bookings.size(); j++) {
                final GeneratedBooking a = bookings.get(i);
                final GeneratedBooking b = bookings.get(j);
                if (!a.roomId().equals(b.roomId()) && overlaps(a, b)) {
                    foundCrossRoomOverlap = true;
                    break;
                }
            }
        }
        assertTrue(foundCrossRoomOverlap, "Expected at least one pair of meetings in different rooms to overlap in time");
    }

    @Test
    void everyBookingStartsAndEndsOnAFiveMinuteBoundary() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(3));

        for (final GeneratedBooking booking : bookings) {
            assertEquals(0, booking.startTime().getSecond());
            assertEquals(0, booking.startTime().getNano());
            assertEquals(0, booking.startTime().getMinute() % 5);
            assertEquals(0, booking.endTime().getSecond());
            assertEquals(0, booking.endTime().getNano());
            assertEquals(0, booking.endTime().getMinute() % 5);
        }
    }

    @Test
    void everyBookingIsWithinBusinessHours() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(8));

        for (final GeneratedBooking booking : bookings) {
            assertFalse(booking.startTime().toLocalTime().isBefore(BUSINESS_DAY_START),
                    "Booking " + booking + " starts before business hours");
            assertFalse(booking.endTime().toLocalTime().isAfter(BUSINESS_DAY_END),
                    "Booking " + booking + " ends after business hours");
        }
    }

    @Test
    void durationsVary() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(4));

        final Set<Long> distinctDurationsMinutes = bookings.stream()
                .map(b -> Duration.between(b.startTime(), b.endTime()).toMinutes())
                .collect(Collectors.toSet());

        assertTrue(distinctDurationsMinutes.size() > 1, "Expected varied meeting lengths, got only " + distinctDurationsMinutes);
    }

    @Test
    void everyBookingRespectsItsRoomCapacity() {
        final List<RoomInfo> rooms = tenRooms();
        final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

        final List<GeneratedBooking> bookings = BookingScheduler.generate(rooms, personIds(), 1,
                SHORT_RANGE_END_DAY_OFFSET, new Random(5));

        for (final GeneratedBooking booking : bookings) {
            final int totalPeople = 1 + booking.attendeeIds().size();
            assertTrue(totalPeople <= capacityByRoomId.get(booking.roomId()),
                    "Booking " + booking + " exceeds room capacity " + capacityByRoomId.get(booking.roomId()));
        }
    }

    @Test
    void everyMeetingHasAtLeastOneAttendeeBesidesTheOrganiser() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(9));

        for (final GeneratedBooking booking : bookings) {
            assertTrue(booking.attendeeIds().size() >= 1,
                    "Booking " + booking + " has no attendees besides the organiser");
        }
    }

    @Test
    void atLeastHalfOfMeetingsUseAtLeastHalfTheRoomCapacity() {
        final List<RoomInfo> rooms = tenRooms();
        final Map<String, Integer> capacityByRoomId = capacityByRoomId(rooms);

        final List<GeneratedBooking> bookings = BookingScheduler.generate(rooms, personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(21));

        final long largeMeetings = bookings.stream()
                .filter(b -> {
                    final int totalPeople = 1 + b.attendeeIds().size();
                    final int capacity = capacityByRoomId.get(b.roomId());
                    return totalPeople >= Math.ceil(capacity / 2.0);
                })
                .count();
        final double fraction = (double) largeMeetings / bookings.size();

        assertTrue(fraction >= 0.5,
                "Expected at least half of meetings to use at least half the room's capacity, got fraction " + fraction);
    }

    @Test
    void atLeastHalfOfMeetingsForEachPersonAreFollowedByAGap() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(42));

        int followedByGap = 0;
        int totalWithNext = 0;
        for (final List<GeneratedBooking> meetings : groupByParticipant(bookings).values()) {
            final List<GeneratedBooking> sorted = meetings.stream()
                    .sorted(Comparator.comparing(GeneratedBooking::startTime))
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
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(11));

        final long startingInFirstTwoHours = bookings.stream()
                .filter(b -> b.startTime().toLocalTime().isBefore(LocalTime.of(10, 0)))
                .count();
        final double fraction = (double) startingInFirstTwoHours / bookings.size();

        assertTrue(fraction < 0.5,
                "Expected fewer than half of meetings to start in the first two business hours, got " + fraction);
    }

    @Test
    void neverSchedulesOnAWeekend() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(13));

        for (final GeneratedBooking booking : bookings) {
            final DayOfWeek dayOfWeek = booking.startTime().getDayOfWeek();
            assertFalse(dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY,
                    "Booking " + booking + " falls on a weekend");
        }
    }

    @Test
    void bookingsSpanFromAWeekAgoToSevenWeeksAhead() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), personIds(),
                FULL_RANGE_START_DAY_OFFSET, FULL_RANGE_END_DAY_OFFSET, new Random(6));

        final LocalDate today = LocalDate.now();
        final LocalDate earliestAllowed = today.minusDays(7);
        final LocalDate latestAllowed = today.plusDays(49);

        for (final GeneratedBooking booking : bookings) {
            final LocalDate day = booking.startTime().toLocalDate();
            assertFalse(day.isBefore(earliestAllowed), "Booking " + booking + " is earlier than allowed");
            assertFalse(day.isAfter(latestAllowed), "Booking " + booking + " is later than allowed");
        }

        assertTrue(bookings.stream().anyMatch(b -> b.startTime().toLocalDate().isBefore(today)),
                "Expected at least one booking in the past");
        assertTrue(bookings.stream().anyMatch(b -> b.startTime().toLocalDate().isAfter(today)),
                "Expected at least one booking in the future");
    }
}
