package com.roombooking.tools.databaserepair;

import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebuildBookingParticipantsRepairTest {

    private static final String BOOKINGS_TABLE = "Bookings";
    private static final String PARTICIPANTS_TABLE = "BookingParticipants";

    private static BookingRecord booking(final String id, final String organiserId, final List<String> attendeeIds) {
        return new BookingRecord(id, organiserId, attendeeIds, "2026-07-01T09:00:00", "2026-07-01T10:00:00");
    }

    @Test
    void createsParticipantRowsMissingForAnExistingBooking() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(BOOKINGS_TABLE, new ArrayList<>(List.of(
                booking("b1", "organiser-1", List.of("attendee-1")).toItem())));

        final RebuildBookingParticipantsRepair.Result result =
                RebuildBookingParticipantsRepair.run(dynamoDbClient, BOOKINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(2, result.created());
        assertEquals(0, result.removed());
        assertEquals(0, result.alreadyCorrect());
        final Set<String> personIds = dynamoDbClient.tables.get(PARTICIPANTS_TABLE).stream()
                .map(item -> item.get("personId").s())
                .collect(Collectors.toSet());
        assertEquals(Set.of("organiser-1", "attendee-1"), personIds);
    }

    @Test
    void skipsParticipantRowsThatAlreadyExist() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final BookingRecord existingBooking = booking("b1", "organiser-1", List.of());
        dynamoDbClient.tables.put(BOOKINGS_TABLE, new ArrayList<>(List.of(existingBooking.toItem())));
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(
                BookingParticipant.allFor(existingBooking).stream().map(BookingParticipant::toItem).toList()));

        final RebuildBookingParticipantsRepair.Result result =
                RebuildBookingParticipantsRepair.run(dynamoDbClient, BOOKINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        assertEquals(1, result.alreadyCorrect());
        assertEquals(1, dynamoDbClient.tables.get(PARTICIPANTS_TABLE).size());
    }

    @Test
    void removesAParticipantRowWhoseBookingNoLongerExists() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final BookingParticipant orphan = new BookingParticipant("person-1", "deleted-booking", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(List.of(orphan.toItem())));

        final RebuildBookingParticipantsRepair.Result result =
                RebuildBookingParticipantsRepair.run(dynamoDbClient, BOOKINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(1, result.removed());
        assertTrue(dynamoDbClient.tables.get(PARTICIPANTS_TABLE).isEmpty());
    }

    @Test
    void dryRunReportsWithoutWritingOrDeletingAnything() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(BOOKINGS_TABLE, new ArrayList<>(List.of(booking("b1", "organiser-1", List.of()).toItem())));
        final BookingParticipant orphan = new BookingParticipant("person-1", "deleted-booking", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(List.of(orphan.toItem())));

        final RebuildBookingParticipantsRepair.Result result =
                RebuildBookingParticipantsRepair.run(dynamoDbClient, BOOKINGS_TABLE, PARTICIPANTS_TABLE, true);

        assertEquals(1, result.created());
        assertEquals(1, result.removed());
        assertEquals(1, dynamoDbClient.tables.get(PARTICIPANTS_TABLE).size(), "dry run must not change the table");
        assertEquals("deleted-booking", dynamoDbClient.tables.get(PARTICIPANTS_TABLE).getFirst().get("bookingId").s());
    }

    @Test
    void succeedsWhenBothTablesAreEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final RebuildBookingParticipantsRepair.Result result =
                RebuildBookingParticipantsRepair.run(dynamoDbClient, BOOKINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        assertEquals(0, result.alreadyCorrect());
    }
}
