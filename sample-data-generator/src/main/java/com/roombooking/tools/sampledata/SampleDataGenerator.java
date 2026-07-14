package com.roombooking.tools.sampledata;

import com.fasterxml.jackson.databind.JsonNode;
import com.roombooking.tools.sampledata.BookingScheduler.GeneratedBooking;
import com.roombooking.tools.sampledata.BookingScheduler.RoomInfo;
import net.datafaker.Faker;

import module java.base;

/**
 * Resets a deployed room-booking-api environment and populates it with realistic sample data:
 * 10 people, 10 rooms, and one booking per (room, day) over the next 3 days (30 bookings total).
 * Run via {@code ./run.sh <environment>} - see that script and this project's README for details.
 */
public final class SampleDataGenerator {

    private static final int PERSON_COUNT = 10;
    private static final int ROOM_COUNT = 10;
    private static final int DAYS_AHEAD = 3;
    private static final int MIN_ROOM_CAPACITY = 4;
    private static final int MAX_ROOM_CAPACITY = 20;
    private static final DateTimeFormatter BOOKING_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private SampleDataGenerator() {
    }

    public static void main(final String[] args) {
        final GraphQlClient client = GraphQlClient.fromEnvironment();
        final Faker faker = new Faker();
        final Random random = new Random();

        System.out.println("Resetting environment...");
        client.execute("mutation { reset }");

        System.out.println("Creating " + PERSON_COUNT + " people...");
        final List<String> personIds = createPeople(client, faker);

        System.out.println("Creating " + ROOM_COUNT + " rooms...");
        final List<RoomInfo> rooms = createRooms(client, random);

        final List<GeneratedBooking> bookings = BookingScheduler.generate(rooms, personIds, DAYS_AHEAD, random);
        System.out.println("Creating " + bookings.size() + " bookings over the next " + DAYS_AHEAD + " days...");
        for (final GeneratedBooking booking : bookings) {
            createBooking(client, booking);
        }

        System.out.println();
        System.out.println("Done: " + personIds.size() + " people, " + rooms.size() + " rooms, "
                + bookings.size() + " bookings created.");
    }

    private static List<String> createPeople(final GraphQlClient client, final Faker faker) {
        final String mutation = "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { id name } }";
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < PERSON_COUNT; i++) {
            final String name = faker.name().fullName();
            final JsonNode result = client.execute(mutation, Map.of("person", Map.of("name", name)));
            final JsonNode person = result.get("createPerson");
            ids.add(person.get("id").asText());
            System.out.println("  " + person.get("name").asText());
        }
        return ids;
    }

    private static List<RoomInfo> createRooms(final GraphQlClient client, final Random random) {
        final String mutation = "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
        final List<String> roomNames = new ArrayList<>(SampleData.ROOM_NAMES);
        Collections.shuffle(roomNames, random);

        final List<RoomInfo> rooms = new ArrayList<>();
        for (int i = 0; i < ROOM_COUNT; i++) {
            final String name = roomNames.get(i);
            final int capacity = MIN_ROOM_CAPACITY + random.nextInt(MAX_ROOM_CAPACITY - MIN_ROOM_CAPACITY + 1);
            final JsonNode result = client.execute(mutation, Map.of("room", Map.of("name", name, "capacity", capacity)));
            final JsonNode payload = result.get("createRoom");
            failIfErrors(payload, "createRoom(" + name + ")");
            final JsonNode room = payload.get("room");
            rooms.add(new RoomInfo(room.get("id").asText(), room.get("capacity").asInt()));
            System.out.println("  " + room.get("name").asText() + " (capacity " + room.get("capacity").asInt() + ")");
        }
        return rooms;
    }

    private static void createBooking(final GraphQlClient client, final GeneratedBooking booking) {
        final String mutation = "mutation CreateBooking($booking: BookingInput!) { "
                + "createBooking(booking: $booking) { booking { id } errors } }";
        final Map<String, Object> input = new HashMap<>();
        input.put("roomId", booking.roomId());
        input.put("organiserId", booking.organiserId());
        input.put("attendeeIds", booking.attendeeIds());
        input.put("subject", booking.subject());
        input.put("startTime", booking.startTime().format(BOOKING_TIME_FORMAT));
        input.put("endTime", booking.endTime().format(BOOKING_TIME_FORMAT));

        final JsonNode result = client.execute(mutation, Map.of("booking", input));
        final JsonNode payload = result.get("createBooking");
        failIfErrors(payload, "createBooking(" + booking.subject() + ")");
        System.out.println("  " + booking.subject() + " - " + booking.startTime().format(BOOKING_TIME_FORMAT)
                + " to " + booking.endTime().format(BOOKING_TIME_FORMAT));
    }

    private static void failIfErrors(final JsonNode payload, final String operationDescription) {
        final JsonNode errors = payload.get("errors");
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalStateException(operationDescription + " was rejected: " + errors);
        }
    }
}
