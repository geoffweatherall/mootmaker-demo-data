package com.roombooking.tools.sampledata;

import module java.base;

/**
 * Pure scheduling logic (no network calls) for generating a realistic-looking set of bookings
 * across a set of rooms over the next few days, within business hours. Meetings are scheduled
 * sequentially within each room (so a room is never double-booked), but different rooms are
 * scheduled independently, so meetings in different rooms may legitimately overlap in time -
 * except that no person (organiser or attendee) is ever placed into two overlapping meetings,
 * tracked via a shared busy-interval map across every room and day.
 */
final class BookingScheduler {

    /** Meeting durations to vary between, all multiples of 5 minutes (the API's boundary rule). */
    private static final List<Integer> DURATION_MINUTES_OPTIONS = List.of(15, 30, 45, 60, 90, 120);

    private static final int BUSINESS_DAY_START_HOUR = 8;
    private static final int BUSINESS_DAY_END_HOUR = 17;

    /** Cap on how many sequential meetings a single room can get in one day. */
    private static final int MAX_MEETINGS_PER_ROOM_PER_DAY = 2;

    /** Chance to stop scheduling a room's day after its first meeting, so room-days vary between 1 and 2 meetings. */
    private static final double CHANCE_TO_STOP_AFTER_FIRST_MEETING = 0.5;

    /**
     * Step used to search for a later start time when the pool of 10 people is fully booked at
     * the current candidate time (e.g. every room's first meeting otherwise starts at 08:00,
     * which only the first ~5 rooms could staff with 2 people each). A multiple of 5 keeps every
     * candidate start on the API's required 5-minute boundary.
     */
    private static final int RETRY_STEP_MINUTES = 15;

    record RoomInfo(String id, int capacity) {
    }

    record GeneratedBooking(String roomId, String subject, String organiserId, List<String> attendeeIds,
            LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** A person's (or room's) busy time range; touching end-to-start is not an overlap, matching the API's own rule. */
    private record Interval(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(final LocalDateTime otherStart, final LocalDateTime otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    private BookingScheduler() {
    }

    /**
     * Generates bookings for {@code daysAhead} business days starting tomorrow, for every room in
     * {@code rooms}. Each room gets 1-2 sequential, non-overlapping meetings per day (skipped once
     * a room/day runs out of business-hours time or of people free at that moment). Every meeting
     * has at least one attendee in addition to its organiser, sized so the room's capacity is
     * never exceeded, and every participant (organiser or attendee) is only ever in one meeting at
     * a time across the whole generated schedule - regardless of room.
     */
    static List<GeneratedBooking> generate(final List<RoomInfo> rooms, final List<String> personIds,
            final int daysAhead, final Random random) {
        final List<GeneratedBooking> bookings = new ArrayList<>();
        final Map<String, List<Interval>> busyByPerson = new HashMap<>();
        final LocalDate today = LocalDate.now();

        for (int dayOffset = 1; dayOffset <= daysAhead; dayOffset++) {
            final LocalDate day = today.plusDays(dayOffset);
            for (final RoomInfo room : rooms) {
                bookings.addAll(generateForRoomDay(room, day, personIds, busyByPerson, random));
            }
        }
        return bookings;
    }

    private static List<GeneratedBooking> generateForRoomDay(final RoomInfo room, final LocalDate day,
            final List<String> personIds, final Map<String, List<Interval>> busyByPerson, final Random random) {
        final List<GeneratedBooking> roomDayBookings = new ArrayList<>();
        final LocalDateTime dayEnd = day.atTime(BUSINESS_DAY_END_HOUR, 0);
        LocalDateTime searchFrom = day.atTime(BUSINESS_DAY_START_HOUR, 0);

        for (int meetingIndex = 0; meetingIndex < MAX_MEETINGS_PER_ROOM_PER_DAY; meetingIndex++) {
            if (meetingIndex > 0 && random.nextDouble() < CHANCE_TO_STOP_AFTER_FIRST_MEETING) {
                break;
            }

            final GeneratedBooking booking = findAndPlaceMeeting(room, dayEnd, searchFrom, personIds, busyByPerson, random);
            if (booking == null) {
                break;
            }
            roomDayBookings.add(booking);
            searchFrom = booking.endTime();
        }
        return roomDayBookings;
    }

    /**
     * Searches forward from {@code searchFrom}, in {@link #RETRY_STEP_MINUTES} steps, for the
     * first time at which both business-hours time remains for some meeting duration AND at least
     * two people are free - since at 08:00 every room's first meeting starts at the same instant,
     * only ~half the rooms can be staffed from a 10-person pool right away, so later rooms need to
     * try later times as earlier, shorter meetings free their participants back up.
     */
    private static GeneratedBooking findAndPlaceMeeting(final RoomInfo room, final LocalDateTime dayEnd,
            final LocalDateTime searchFrom, final List<String> personIds, final Map<String, List<Interval>> busyByPerson,
            final Random random) {
        for (LocalDateTime candidateStart = searchFrom; candidateStart.isBefore(dayEnd);
                candidateStart = candidateStart.plusMinutes(RETRY_STEP_MINUTES)) {
            final LocalDateTime startTime = candidateStart;
            final List<Integer> feasibleDurations = DURATION_MINUTES_OPTIONS.stream()
                    .filter(minutes -> !startTime.plusMinutes(minutes).isAfter(dayEnd))
                    .toList();
            if (feasibleDurations.isEmpty()) {
                return null;
            }

            final int durationMinutes = feasibleDurations.get(random.nextInt(feasibleDurations.size()));
            final LocalDateTime endTime = startTime.plusMinutes(durationMinutes);

            final List<String> freePeople = new ArrayList<>(personIds);
            Collections.shuffle(freePeople, random);
            freePeople.removeIf(personId -> isBusy(busyByPerson, personId, startTime, endTime));

            // Need at least an organiser plus one attendee; if too contended right now, try later.
            if (freePeople.size() < 2) {
                continue;
            }

            final String organiserId = freePeople.getFirst();
            final int maxAttendees = Math.min(room.capacity() - 1, freePeople.size() - 1);
            final int attendeeCount = 1 + (maxAttendees > 1 ? random.nextInt(maxAttendees) : 0);
            final List<String> attendeeIds = List.copyOf(freePeople.subList(1, 1 + attendeeCount));

            markBusy(busyByPerson, organiserId, startTime, endTime);
            for (final String attendeeId : attendeeIds) {
                markBusy(busyByPerson, attendeeId, startTime, endTime);
            }

            final String subject = SampleData.MEETING_SUBJECTS.get(random.nextInt(SampleData.MEETING_SUBJECTS.size()));
            return new GeneratedBooking(room.id(), subject, organiserId, attendeeIds, startTime, endTime);
        }
        return null;
    }

    private static boolean isBusy(final Map<String, List<Interval>> busyByPerson, final String personId,
            final LocalDateTime start, final LocalDateTime end) {
        return busyByPerson.getOrDefault(personId, List.of()).stream().anyMatch(interval -> interval.overlaps(start, end));
    }

    private static void markBusy(final Map<String, List<Interval>> busyByPerson, final String personId,
            final LocalDateTime start, final LocalDateTime end) {
        busyByPerson.computeIfAbsent(personId, key -> new ArrayList<>()).add(new Interval(start, end));
    }
}
