package com.mootmaker.tools.sampledatatopup;

import com.fasterxml.jackson.databind.JsonNode;
import com.mootmaker.tools.sampledatatopup.MeetingScheduler.GeneratedMeeting;
import com.mootmaker.tools.sampledatatopup.MeetingScheduler.RoomInfo;

import module java.base;

/**
 * Looks ahead {@value #WEEKS_AHEAD} weeks from today, in a deployed mootmaker-api environment,
 * and creates sample meetings for any weekday (Monday-Friday - it's normal and expected for a
 * Saturday or Sunday to have none) that currently has <b>no</b> meetings at all, using the same
 * room-booking rules as sample-data-generator's {@code MeetingScheduler}.
 *
 * <p>Unlike sample-data-generator, this never resets or deletes anything - it only ever adds
 * meetings to already-empty days, reusing whatever rooms and people (real signed-up users and any
 * earlier sample data alike) already exist. That's what makes it safe to run unattended on a
 * schedule, including against `production` (see this project's README): a day with even one real
 * meeting on it is left alone, and no room, person, or existing meeting is ever touched. Deployed
 * as a Lambda function invoked weekly by an EventBridge schedule (see deploy/terraform/), or
 * on-demand via {@code ./run.sh <environment>}.
 */
final class SampleDataTopUp {

    /** How far ahead to look for empty business days that need topping up. */
    private static final int WEEKS_AHEAD = 6;
    private static final int WINDOW_DAYS = WEEKS_AHEAD * 7;

    /** Every meeting needs an organiser plus at least one attendee - mirrors MeetingScheduler's own floor. */
    private static final int MIN_BOOKABLE_PEOPLE = 2;

    private static final DateTimeFormatter MEETING_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Same reasoning as sample-data-generator's own MAX_CONCURRENT_REQUESTS: each createMeeting
     * call is an independent network round trip, MeetingScheduler has already resolved a
     * conflict-free schedule up front, so the calls can safely run in parallel rather than one at
     * a time - capped well below AppSync/Lambda's default concurrency limits.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    private SampleDataTopUp() {
    }

    /** Summary of a completed run, returned as the Lambda invocation's response payload. */
    record Summary(int weekdaysToppedUp, int meetingsCreated) {
    }

    static Summary run(final GraphQlClient client) {
        final LocalDate today = LocalDate.now();
        final LocalDate windowEnd = today.plusDays(WINDOW_DAYS);

        System.out.println("Checking for empty weekdays between " + today + " and " + windowEnd.minusDays(1) + "...");

        // Rooms, people, and existing meetings are three independent reads, so they run
        // concurrently rather than one after another.
        final ExecutorService executor = Executors.newFixedThreadPool(3);
        final List<RoomInfo> rooms;
        final List<String> personIds;
        final Set<LocalDate> datesWithMeetings;
        try {
            final Future<List<RoomInfo>> roomsFuture = executor.submit(() -> fetchRooms(client));
            final Future<List<String>> peopleFuture = executor.submit(() -> fetchPersonIds(client));
            final Future<Set<LocalDate>> meetingDatesFuture =
                    executor.submit(() -> fetchDatesWithMeetings(client, today, windowEnd));

            rooms = getResult(roomsFuture);
            personIds = getResult(peopleFuture);
            datesWithMeetings = getResult(meetingDatesFuture);
        } finally {
            executor.shutdown();
        }

        final List<LocalDate> targetDays = weekdaysBetween(today, windowEnd).stream()
                .filter(day -> !datesWithMeetings.contains(day))
                .toList();

        if (targetDays.isEmpty()) {
            System.out.println("Every weekday in the window already has at least one meeting - nothing to do.");
            return new Summary(0, 0);
        }
        System.out.println("Found " + targetDays.size() + " empty weekday(s): " + targetDays);

        if (rooms.isEmpty() || personIds.size() < MIN_BOOKABLE_PEOPLE) {
            System.out.println("Skipping: need at least one room and " + MIN_BOOKABLE_PEOPLE
                    + " people to book a meeting, found " + rooms.size() + " room(s) and " + personIds.size()
                    + " person(s). Run sample-data-generator first to seed some.");
            return new Summary(0, 0);
        }

        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(rooms, personIds, targetDays, new Random());
        System.out.println("Creating " + meetings.size() + " meeting(s)...");
        runInParallel(meetings, meeting -> createMeeting(client, meeting));

        System.out.println("Done: " + targetDays.size() + " weekday(s) topped up, " + meetings.size() + " meeting(s) created.");
        return new Summary(targetDays.size(), meetings.size());
    }

    /**
     * Every Monday-Friday date from {@code startInclusive} up to (but not including) {@code
     * endExclusive}. Package-private (rather than private) so tests can exercise it directly.
     */
    static List<LocalDate> weekdaysBetween(final LocalDate startInclusive, final LocalDate endExclusive) {
        final List<LocalDate> weekdays = new ArrayList<>();
        for (LocalDate day = startInclusive; day.isBefore(endExclusive); day = day.plusDays(1)) {
            final DayOfWeek dayOfWeek = day.getDayOfWeek();
            if (dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) {
                weekdays.add(day);
            }
        }
        return weekdays;
    }

    private static List<RoomInfo> fetchRooms(final GraphQlClient client) {
        final JsonNode result = client.execute("query { rooms { id capacity } }");
        final List<RoomInfo> rooms = new ArrayList<>();
        for (final JsonNode room : result.get("rooms")) {
            rooms.add(new RoomInfo(room.get("id").asText(), room.get("capacity").asInt()));
        }
        return rooms;
    }

    private static List<String> fetchPersonIds(final GraphQlClient client) {
        final JsonNode result = client.execute("query { people { id } }");
        final List<String> personIds = new ArrayList<>();
        for (final JsonNode person : result.get("people")) {
            personIds.add(person.get("id").asText());
        }
        return personIds;
    }

    /** The calendar dates (within the window) that already have at least one meeting, in any room. */
    private static Set<LocalDate> fetchDatesWithMeetings(final GraphQlClient client, final LocalDate windowStart,
            final LocalDate windowEnd) {
        final String query = "query FilterMeetings($filter: MeetingsFilter) { meetings(filter: $filter) { startTime } }";
        final Map<String, Object> filter = Map.of(
                "fromStartTime", windowStart.atStartOfDay().format(MEETING_TIME_FORMAT),
                "toEndTime", windowEnd.atStartOfDay().format(MEETING_TIME_FORMAT));

        final JsonNode result = client.execute(query, Map.of("filter", filter));
        final Set<LocalDate> dates = new HashSet<>();
        for (final JsonNode meeting : result.get("meetings")) {
            dates.add(LocalDateTime.parse(meeting.get("startTime").asText()).toLocalDate());
        }
        return dates;
    }

    private static void createMeeting(final GraphQlClient client, final GeneratedMeeting meeting) {
        final String mutation = "mutation CreateMeeting($meeting: MeetingInput!) { "
                + "createMeeting(meeting: $meeting) { meeting { id } errors } }";
        final Map<String, Object> input = new HashMap<>();
        input.put("roomId", meeting.roomId());
        input.put("organiserId", meeting.organiserId());
        input.put("attendeeIds", meeting.attendeeIds());
        input.put("subject", meeting.subject());
        input.put("startTime", meeting.startTime().format(MEETING_TIME_FORMAT));
        input.put("endTime", meeting.endTime().format(MEETING_TIME_FORMAT));

        final JsonNode result = client.execute(mutation, Map.of("meeting", input));
        final JsonNode payload = result.get("createMeeting");
        final JsonNode errors = payload.get("errors");
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalStateException("createMeeting(" + meeting.subject() + ") was rejected: " + errors);
        }
        System.out.println("  " + meeting.subject() + " - " + meeting.startTime().format(MEETING_TIME_FORMAT)
                + " to " + meeting.endTime().format(MEETING_TIME_FORMAT));
    }

    private static <T> T getResult(final Future<T> future) {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching existing rooms/people/meetings", e);
        }
    }

    /**
     * Runs {@code action} for every item, on a bounded pool of {@value #MAX_CONCURRENT_REQUESTS}
     * threads, and waits for them all to finish. The first failure is rethrown after every task
     * has completed, same as a sequential loop would have failed on the first bad item - just not
     * necessarily the same item, since order isn't guaranteed under parallel execution.
     * Package-private (rather than private) so {@code SampleDataTopUpConcurrencyTest} can exercise
     * it directly. Identical to sample-data-generator's {@code SampleDataGenerator.runInParallel}.
     */
    static <T> void runInParallel(final List<T> items, final Consumer<T> action) {
        if (items.isEmpty()) {
            return;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, items.size()));
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
                        firstFailure = cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException(cause);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for parallel tasks to finish", e);
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
