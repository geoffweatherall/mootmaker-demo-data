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
    private static final int DAYS_AHEAD = 3;
    private static final LocalTime BUSINESS_DAY_START = LocalTime.of(8, 0);
    private static final LocalTime BUSINESS_DAY_END = LocalTime.of(17, 0);

    private static List<RoomInfo> tenRooms() {
        final List<RoomInfo> rooms = new ArrayList<>();
        for (int i = 0; i < ROOM_COUNT; i++) {
            rooms.add(new RoomInfo("room-" + i, 2 + i));
        }
        return rooms;
    }

    private static List<String> tenPersonIds() {
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add("person-" + i);
        }
        return ids;
    }

    private static boolean overlaps(final GeneratedBooking a, final GeneratedBooking b) {
        return a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime());
    }

    @Test
    void generatesAtLeastOneBookingPerRoomOnAverage() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(1));

        assertTrue(bookings.size() >= ROOM_COUNT, "Expected at least one booking per room, got " + bookings.size());
        assertTrue(bookings.size() <= ROOM_COUNT * DAYS_AHEAD * 2, "Got implausibly many bookings: " + bookings.size());
    }

    @Test
    void neverOverlapsTwoBookingsInTheSameRoom() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(2));

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
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(7));

        final Map<String, List<GeneratedBooking>> byParticipant = new HashMap<>();
        for (final GeneratedBooking booking : bookings) {
            byParticipant.computeIfAbsent(booking.organiserId(), _ -> new ArrayList<>()).add(booking);
            for (final String attendeeId : booking.attendeeIds()) {
                byParticipant.computeIfAbsent(attendeeId, _ -> new ArrayList<>()).add(booking);
            }
        }

        for (final Map.Entry<String, List<GeneratedBooking>> entry : byParticipant.entrySet()) {
            final List<GeneratedBooking> participantBookings = entry.getValue();
            for (int i = 0; i < participantBookings.size(); i++) {
                for (int j = i + 1; j < participantBookings.size(); j++) {
                    final GeneratedBooking a = participantBookings.get(i);
                    final GeneratedBooking b = participantBookings.get(j);
                    assertFalse(overlaps(a, b), "Person " + entry.getKey() + " is double-booked in " + a + " and " + b);
                }
            }
        }
    }

    @Test
    void someMeetingsInDifferentRoomsOverlapInTime() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(2));

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
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(3));

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
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(8));

        for (final GeneratedBooking booking : bookings) {
            assertFalse(booking.startTime().toLocalTime().isBefore(BUSINESS_DAY_START),
                    "Booking " + booking + " starts before business hours");
            assertFalse(booking.endTime().toLocalTime().isAfter(BUSINESS_DAY_END),
                    "Booking " + booking + " ends after business hours");
        }
    }

    @Test
    void durationsVary() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(4));

        final Set<Long> distinctDurationsMinutes = bookings.stream()
                .map(b -> Duration.between(b.startTime(), b.endTime()).toMinutes())
                .collect(Collectors.toSet());

        assertTrue(distinctDurationsMinutes.size() > 1, "Expected varied meeting lengths, got only " + distinctDurationsMinutes);
    }

    @Test
    void everyBookingHasAtLeastOneAttendeeAndRespectsItsRoomCapacity() {
        final List<RoomInfo> rooms = tenRooms();
        final Map<String, Integer> capacityByRoomId = new HashMap<>();
        for (final RoomInfo room : rooms) {
            capacityByRoomId.put(room.id(), room.capacity());
        }

        final List<GeneratedBooking> bookings = BookingScheduler.generate(rooms, tenPersonIds(), DAYS_AHEAD, new Random(5));

        for (final GeneratedBooking booking : bookings) {
            assertTrue(booking.attendeeIds().size() >= 1, "Booking " + booking + " has no attendees besides the organiser");
            final int totalPeople = 1 + booking.attendeeIds().size();
            assertTrue(totalPeople <= capacityByRoomId.get(booking.roomId()),
                    "Booking " + booking + " exceeds room capacity " + capacityByRoomId.get(booking.roomId()));
        }
    }

    @Test
    void everyBookingIsInTheFuture() {
        final List<GeneratedBooking> bookings = BookingScheduler.generate(tenRooms(), tenPersonIds(), DAYS_AHEAD, new Random(6));

        final LocalDateTime now = LocalDateTime.now();
        for (final GeneratedBooking booking : bookings) {
            assertTrue(booking.startTime().isAfter(now), "Booking " + booking + " is not in the future");
        }
    }
}
