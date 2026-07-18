package com.mootmaker.tools.sampledata;

import com.fasterxml.jackson.databind.JsonNode;
import com.mootmaker.tools.sampledata.MeetingScheduler.GeneratedMeeting;
import com.mootmaker.tools.sampledata.MeetingScheduler.RoomInfo;
import net.datafaker.Faker;

import module java.base;

/**
 * Resets a deployed mootmaker-api environment and populates it with realistic sample data:
 * 40 newly-created people, 10 rooms, and meetings across every business day from a week ago to
 * seven weeks from now. Anyone already in the system with a linked Cognito account (real users who
 * have signed up - the only people {@code reset} doesn't delete) is booked into meetings
 * alongside the 40 new people, rather than only the newly-created ones. Run via
 * {@code ./run.sh <environment>} - see that script and this project's README for details.
 */
public final class SampleDataGenerator {

    private static final int PERSON_COUNT = 40;
    private static final int ROOM_COUNT = 10;
    private static final int DAYS_IN_PAST = 7;
    private static final int DAYS_IN_FUTURE = 49;
    private static final int MIN_ROOM_CAPACITY = 4;
    private static final int MAX_ROOM_CAPACITY = 20;
    private static final DateTimeFormatter MEETING_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Every createPerson/createRoom/createMeeting call is an independent network round trip (each
     * one its own AppSync request + Lambda invocation), and - critically - {@link MeetingScheduler}
     * has already resolved the full schedule up front with no overlapping room or person times
     * across the whole run (see its own tests), so the order meetings are actually submitted in
     * doesn't matter and they can safely go in parallel rather than one at a time. Capped well
     * below AppSync/Lambda's default concurrency limits so this doesn't look like a burst of
     * traffic against a small demo deployment or exhaust the JVM's HTTP connection pool.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    private SampleDataGenerator() {
    }

    public static void main(final String[] args) {
        final GraphQlClient client = GraphQlClient.fromEnvironment();
        final Faker faker = new Faker();
        final Random random = new Random();

        System.out.println("Resetting environment...");
        client.execute("mutation { reset }");

        // Only people linked to a Cognito account survive reset, so whatever's left at this point
        // is exactly that set - real signed-up users, not sample data from a previous run.
        final List<PersonInfo> existingPeople = fetchExistingPeople(client);
        if (!existingPeople.isEmpty()) {
            System.out.println("Found " + existingPeople.size()
                    + " existing person(s) with a Cognito account; including them when scheduling meetings...");
            for (final PersonInfo person : existingPeople) {
                System.out.println("  " + person.name());
            }
        }

        System.out.println("Creating " + PERSON_COUNT + " people...");
        final List<String> newPersonIds = createPeople(client, faker);

        final List<String> bookablePersonIds = new ArrayList<>(newPersonIds.size() + existingPeople.size());
        bookablePersonIds.addAll(newPersonIds);
        for (final PersonInfo person : existingPeople) {
            bookablePersonIds.add(person.id());
        }

        System.out.println("Creating " + ROOM_COUNT + " rooms...");
        final List<RoomInfo> rooms = createRooms(client, random);

        final List<GeneratedMeeting> meetings = MeetingScheduler.generate(rooms, bookablePersonIds, -DAYS_IN_PAST,
                DAYS_IN_FUTURE, random);
        System.out.println("Creating " + meetings.size() + " meetings from " + DAYS_IN_PAST + " days ago to "
                + DAYS_IN_FUTURE + " days ahead...");
        runInParallel(meetings, meeting -> createMeeting(client, meeting));

        System.out.println();
        System.out.println("Done: " + newPersonIds.size() + " new people (+" + existingPeople.size()
                + " existing Cognito-linked person(s)), " + rooms.size() + " rooms, " + meetings.size()
                + " meetings created.");
    }

    private record PersonInfo(String id, String name) {
    }

    private static List<PersonInfo> fetchExistingPeople(final GraphQlClient client) {
        final JsonNode result = client.execute("query { people { id name } }");
        final List<PersonInfo> people = new ArrayList<>();
        for (final JsonNode person : result.get("people")) {
            people.add(new PersonInfo(person.get("id").asText(), person.get("name").asText()));
        }
        return people;
    }

    private static List<String> createPeople(final GraphQlClient client, final Faker faker) {
        final String mutation = "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { id name } }";

        // Faker/Random aren't safe for concurrent use, so names are generated up front,
        // sequentially; only the network calls below run in parallel.
        final List<String> names = new ArrayList<>(PERSON_COUNT);
        for (int i = 0; i < PERSON_COUNT; i++) {
            names.add(faker.name().fullName());
        }

        final String[] ids = new String[PERSON_COUNT];
        // Indexed rather than iterating `names` directly - two Faker-generated names could
        // coincidentally collide, and looking up "the index of this name" would then corrupt data
        // by writing both people's ids to the same (first-matching) slot.
        runInParallel(IntStream.range(0, PERSON_COUNT).boxed().toList(), i -> {
            final JsonNode result = client.execute(mutation, Map.of("person", Map.of("name", names.get(i))));
            final JsonNode person = result.get("createPerson");
            ids[i] = person.get("id").asText();
            System.out.println("  " + person.get("name").asText());
        });
        return List.of(ids);
    }

    private static List<RoomInfo> createRooms(final GraphQlClient client, final Random random) {
        final String mutation = "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
        final List<String> roomNames = new ArrayList<>(SampleData.ROOM_NAMES);
        Collections.shuffle(roomNames, random);

        // Random isn't safe for concurrent use, so capacities are generated up front, sequentially;
        // only the network calls below run in parallel.
        final int[] capacities = new int[ROOM_COUNT];
        for (int i = 0; i < ROOM_COUNT; i++) {
            capacities[i] = MIN_ROOM_CAPACITY + random.nextInt(MAX_ROOM_CAPACITY - MIN_ROOM_CAPACITY + 1);
        }

        final RoomInfo[] rooms = new RoomInfo[ROOM_COUNT];
        runInParallel(IntStream.range(0, ROOM_COUNT).boxed().toList(), i -> {
            final String name = roomNames.get(i);
            final JsonNode result = client.execute(mutation, Map.of("room", Map.of("name", name, "capacity", capacities[i])));
            final JsonNode payload = result.get("createRoom");
            failIfErrors(payload, "createRoom(" + name + ")");
            final JsonNode room = payload.get("room");
            rooms[i] = new RoomInfo(room.get("id").asText(), room.get("capacity").asInt());
            System.out.println("  " + room.get("name").asText() + " (capacity " + room.get("capacity").asInt() + ")");
        });
        return List.of(rooms);
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
        failIfErrors(payload, "createMeeting(" + meeting.subject() + ")");
        System.out.println("  " + meeting.subject() + " - " + meeting.startTime().format(MEETING_TIME_FORMAT)
                + " to " + meeting.endTime().format(MEETING_TIME_FORMAT));
    }

    private static void failIfErrors(final JsonNode payload, final String operationDescription) {
        final JsonNode errors = payload.get("errors");
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalStateException(operationDescription + " was rejected: " + errors);
        }
    }

    /**
     * Runs {@code action} for every item, on a bounded pool of {@value #MAX_CONCURRENT_REQUESTS}
     * threads, and waits for them all to finish. The first failure (e.g. a rejected meeting) is
     * rethrown after every task has completed, same as the sequential loop this replaces would
     * have failed on the first bad item - just not necessarily the same item, since order isn't
     * guaranteed under parallel execution. Package-private (rather than private) so
     * {@code SampleDataGeneratorConcurrencyTest} can exercise it directly.
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
