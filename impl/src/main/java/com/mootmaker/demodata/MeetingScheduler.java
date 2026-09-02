package com.mootmaker.demodata;

import module java.base;

/**
 * Pure scheduling logic (no network calls) for generating a realistic-looking set of meetings
 * across a set of rooms for a specific list of business days. Meetings are scheduled sequentially
 * within each room (so a room is never double-booked), but different rooms are scheduled
 * independently, so meetings in different rooms may legitimately overlap in time - except that no
 * person (organiser or attendee) is ever placed into two overlapping meetings, tracked via a
 * shared busy-interval map across every room and day.
 *
 * <p>Takes an explicit {@code List<LocalDate>} of days to generate for, rather than a contiguous
 * day-offset range: {@link DemoData} has already worked out exactly which business days are empty
 * and need topping up, and those need not be contiguous (e.g. a single empty weekday sandwiched
 * between two already-populated ones). This class used to exist twice - once per tool - and the
 * two copies had already drifted 53 lines apart on exactly this point, the generator's copy taking
 * a contiguous range. The merge deleted that fork; the general form here absorbs the specific one,
 * since "every business day in the window" is just a list the caller computes.
 *
 * <p>A few extra touches keep the generated data looking like a real calendar rather than a packed
 * schedule: each room's first meeting of the day starts at a random
 * point in the day (not always 08:00), a person is, more often than not, given a real gap before
 * their next meeting rather than being booked back-to-back, and meetings vary between small
 * catch-ups and room-filling sessions that use at least half the room's capacity.
 */
final class MeetingScheduler {

    /** Meeting durations to vary between, all multiples of 15 minutes (the API's boundary rule). */
    private static final List<Integer> DURATION_MINUTES_OPTIONS = List.of(15, 30, 45, 60, 90, 120);

    private static final int BUSINESS_DAY_START_HOUR = 8;
    private static final int BUSINESS_DAY_END_HOUR = 17;
    private static final int BUSINESS_DAY_MINUTES = (BUSINESS_DAY_END_HOUR - BUSINESS_DAY_START_HOUR) * 60;

    /** Cap on how many sequential meetings a single room can get in one day. */
    private static final int MAX_MEETINGS_PER_ROOM_PER_DAY = 2;

    /** Chance to stop scheduling a room's day after its first meeting, so room-days vary between 1 and 2 meetings. */
    private static final double CHANCE_TO_STOP_AFTER_FIRST_MEETING = 0.5;

    /**
     * Step used both to search for a later start time when people are contended, and to pick a
     * room-day's random starting point. A multiple of 15 keeps every candidate start on the API's
     * required 15-minute boundary.
     */
    private static final int RETRY_STEP_MINUTES = 15;

    /**
     * Chance that a participant is given a real gap before they can be booked into another
     * meeting, rather than being immediately available the instant this meeting ends. Comfortably
     * above the "at least half" target since some participants who don't get an artificial gap
     * still end up with one anyway (the next room to look for them may not do so immediately).
     */
    private static final double GAP_AFTER_MEETING_PROBABILITY = 0.65;

    /** How long that post-meeting gap lasts, when one is applied. */
    private static final List<Integer> GAP_MINUTES_OPTIONS = List.of(15, 30, 45, 60);

    /** Every meeting needs an organiser plus at least one attendee. */
    private static final int MIN_PARTICIPANTS = 2;

    /**
     * Chance that a meeting is deliberately sized to use at least half the room's capacity, rather
     * than being a small catch-up. Comfortably above the "at least half of all meetings" target
     * since a meeting occasionally can't reach that size if too few people are free right now (it's
     * simply capped to however many are available, rather than dropped or delayed).
     */
    private static final double LARGE_MEETING_PROBABILITY = 0.65;

    /**
     * Weighted attendee-count outcomes for a small (non room-filling) meeting, before capping to
     * what the free-people pool allows: one attendee besides the organiser is the most common case,
     * tapering off from there. Keeping most small meetings this size leaves more people free at any
     * given moment, which is what makes it possible to also give most people real gaps between
     * meetings and still staff the occasional large meeting.
     */
    private static final double[] SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS = {0.55, 0.85, 1.0};

    record RoomInfo(String id, int capacity) {
    }

    record GeneratedMeeting(String roomId, String subject, String organiserId, List<String> attendeeIds,
            LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** A person's (or room's) busy time range; touching end-to-start is not an overlap, matching the API's own rule. */
    private record Interval(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(final LocalDateTime otherStart, final LocalDateTime otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    private MeetingScheduler() {
    }

    /**
     * Generates meetings for every room, for every day in {@code targetDays} (assumed already
     * filtered to weekdays with no existing meetings - this method doesn't check either). Each
     * room gets 0-2 sequential, non-overlapping meetings per day (fewer if the day's random
     * starting point leaves little business-hours time, or people are scarce). Every meeting has
     * an organiser plus at least one attendee, sized so the room's capacity is never exceeded; at
     * least half of all meetings use at least half the room's capacity, the rest are small
     * catch-ups. Every participant (organiser or attendee) is only ever in one meeting at a time
     * across the whole generated batch, regardless of room or day.
     */
    static List<GeneratedMeeting> generate(final List<RoomInfo> rooms, final List<String> personIds,
            final List<LocalDate> targetDays, final Random random) {
        final List<GeneratedMeeting> meetings = new ArrayList<>();
        final Map<String, List<Interval>> busyByPerson = new HashMap<>();

        for (final LocalDate day : targetDays) {
            for (final RoomInfo room : rooms) {
                meetings.addAll(generateForRoomDay(room, day, personIds, busyByPerson, random));
            }
        }
        return meetings;
    }

    private static List<GeneratedMeeting> generateForRoomDay(final RoomInfo room, final LocalDate day,
            final List<String> personIds, final Map<String, List<Interval>> busyByPerson, final Random random) {
        final List<GeneratedMeeting> roomDayMeetings = new ArrayList<>();
        final LocalDateTime dayEnd = day.atTime(BUSINESS_DAY_END_HOUR, 0);
        LocalDateTime searchFrom = randomStartOfDay(day, random);

        for (int meetingIndex = 0; meetingIndex < MAX_MEETINGS_PER_ROOM_PER_DAY; meetingIndex++) {
            if (meetingIndex > 0 && random.nextDouble() < CHANCE_TO_STOP_AFTER_FIRST_MEETING) {
                break;
            }

            final GeneratedMeeting meeting = findAndPlaceMeeting(room, dayEnd, searchFrom, personIds, busyByPerson, random);
            if (meeting == null) {
                break;
            }
            roomDayMeetings.add(meeting);
            searchFrom = meeting.endTime();
        }
        return roomDayMeetings;
    }

    /**
     * Picks a random point within the business day to start looking for this room's first
     * meeting, so rooms' meetings don't all cluster at 08:00 - some rooms will end up starting
     * (and finishing) their day late, or not being used at all, which is realistic.
     */
    private static LocalDateTime randomStartOfDay(final LocalDate day, final Random random) {
        final int offsetSteps = random.nextInt(BUSINESS_DAY_MINUTES / RETRY_STEP_MINUTES);
        return day.atTime(BUSINESS_DAY_START_HOUR, 0).plusMinutes((long) offsetSteps * RETRY_STEP_MINUTES);
    }

    /**
     * Searches forward from {@code searchFrom}, in {@link #RETRY_STEP_MINUTES} steps, for the
     * first time at which both business-hours time remains for some meeting duration AND at least
     * {@link #MIN_PARTICIPANTS} people are free.
     */
    private static GeneratedMeeting findAndPlaceMeeting(final RoomInfo room, final LocalDateTime dayEnd,
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
            if (freePeople.size() < MIN_PARTICIPANTS) {
                continue;
            }

            final boolean scheduleAsLargeMeeting = random.nextDouble() < LARGE_MEETING_PROBABILITY;
            // At least MIN_PARTICIPANTS even when "half capacity" rounds down below it (e.g. a
            // capacity-2 room's half is 1, which alone wouldn't leave room for an attendee).
            final int desiredParticipants = Math.max(MIN_PARTICIPANTS, scheduleAsLargeMeeting
                    ? (int) Math.ceil(room.capacity() / 2.0)
                    : 1 + pickSmallMeetingAttendeeCount(random));
            // Capped to whatever the room and the free-people pool actually allow, but never below
            // the organiser-plus-one-attendee floor already guaranteed by the check above.
            final int totalParticipants = Math.min(desiredParticipants, Math.min(room.capacity(), freePeople.size()));
            final int attendeeCount = totalParticipants - 1;

            final String organiserId = freePeople.getFirst();
            final List<String> attendeeIds = List.copyOf(freePeople.subList(1, 1 + attendeeCount));

            markBusyWithOptionalGap(busyByPerson, organiserId, startTime, endTime, random);
            for (final String attendeeId : attendeeIds) {
                markBusyWithOptionalGap(busyByPerson, attendeeId, startTime, endTime, random);
            }

            final String subject = SampleData.MEETING_SUBJECTS.get(random.nextInt(SampleData.MEETING_SUBJECTS.size()));
            return new GeneratedMeeting(room.id(), subject, organiserId, attendeeIds, startTime, endTime);
        }
        return null;
    }

    /**
     * Rolls a weighted attendee count for a small meeting (see
     * {@link #SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS}); always at least 1.
     */
    private static int pickSmallMeetingAttendeeCount(final Random random) {
        final double roll = random.nextDouble();
        for (int i = 0; i < SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS.length; i++) {
            if (roll < SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS[i]) {
                return i + 1;
            }
        }
        return SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS.length;
    }

    private static boolean isBusy(final Map<String, List<Interval>> busyByPerson, final String personId,
            final LocalDateTime start, final LocalDateTime end) {
        return busyByPerson.getOrDefault(personId, List.of()).stream().anyMatch(interval -> interval.overlaps(start, end));
    }

    /**
     * Marks a participant busy for the meeting's real duration and, most of the time, for a
     * further random gap afterwards - so they're not picked for another meeting the instant this
     * one ends. The gap only affects when this person can next be scheduled; the meeting's actual
     * start/end times are unaffected.
     */
    private static void markBusyWithOptionalGap(final Map<String, List<Interval>> busyByPerson, final String personId,
            final LocalDateTime start, final LocalDateTime end, final Random random) {
        LocalDateTime busyUntil = end;
        if (random.nextDouble() < GAP_AFTER_MEETING_PROBABILITY) {
            final int gapMinutes = GAP_MINUTES_OPTIONS.get(random.nextInt(GAP_MINUTES_OPTIONS.size()));
            busyUntil = end.plusMinutes(gapMinutes);
        }
        busyByPerson.computeIfAbsent(personId, key -> new ArrayList<>()).add(new Interval(start, busyUntil));
    }
}
