package com.roombooking.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import module java.base;

/**
 * Repair #2: booking-participants is a derived index of the bookings table (see room-booking-api's
 * {@code BookingParticipant}) - one row per (booking, organiser-or-attendee) pair, letting
 * {@code Query.bookings}' {@code personId} filter find a person's bookings without scanning
 * {@code attendeeIds} (a list, so it can't be a GSI key). {@code CreateBookingHandler} writes a
 * booking and its participant rows atomically, so under normal operation the two never drift - but
 * this table has no rows at all for any booking created before it existed, and a manual data fix
 * or a restored table could leave it out of sync. This repair recomputes the exact expected
 * participant rows from the bookings table (the source of truth) and reconciles: creates rows that
 * are missing, and removes rows that don't belong (either their booking no longer exists, or the
 * (person, booking) pair isn't actually one of that booking's participants). Only ever touches
 * booking-participants, never the bookings table itself, so - like {@link CreateMissingPersonsRepair}
 * - this is safe to run against a real/production environment.
 */
final class RebuildBookingParticipantsRepair {

    private RebuildBookingParticipantsRepair() {
    }

    record Result(int created, int removed, int alreadyCorrect) {
    }

    static Result run(final DynamoDbClient dynamoDbClient, final String bookingsTableName,
            final String bookingParticipantsTableName, final boolean dryRun) {
        final List<BookingRecord> bookings = scan(dynamoDbClient, bookingsTableName, BookingRecord::fromItem);
        System.out.println("Found " + bookings.size() + " booking(s).");

        final Map<String, BookingParticipant> expected = new LinkedHashMap<>();
        for (final BookingRecord booking : bookings) {
            for (final BookingParticipant participant : BookingParticipant.allFor(booking)) {
                expected.put(key(participant), participant);
            }
        }

        final Map<String, BookingParticipant> existing = new LinkedHashMap<>();
        for (final BookingParticipant participant : scan(dynamoDbClient, bookingParticipantsTableName, BookingParticipant::fromItem)) {
            existing.put(key(participant), participant);
        }

        int created = 0;
        int alreadyCorrect = 0;
        for (final Map.Entry<String, BookingParticipant> entry : expected.entrySet()) {
            if (existing.containsKey(entry.getKey())) {
                alreadyCorrect++;
                continue;
            }
            final BookingParticipant participant = entry.getValue();
            System.out.println("  creating participant row: person " + participant.personId() + ", booking "
                    + participant.bookingId() + (dryRun ? " (dry run)" : ""));
            if (!dryRun) {
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(bookingParticipantsTableName)
                        .item(participant.toItem())
                        .build());
            }
            created++;
        }

        int removed = 0;
        for (final Map.Entry<String, BookingParticipant> entry : existing.entrySet()) {
            if (expected.containsKey(entry.getKey())) {
                continue;
            }
            final BookingParticipant participant = entry.getValue();
            System.out.println("  removing stale participant row: person " + participant.personId() + ", booking "
                    + participant.bookingId() + (dryRun ? " (dry run)" : ""));
            if (!dryRun) {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(bookingParticipantsTableName)
                        .key(Map.of(
                                "personId", AttributeValue.builder().s(participant.personId()).build(),
                                "sortKey", AttributeValue.builder().s(participant.sortKey()).build()))
                        .build());
            }
            removed++;
        }

        return new Result(created, removed, alreadyCorrect);
    }

    private static String key(final BookingParticipant participant) {
        return participant.personId() + "|" + participant.bookingId();
    }

    private static <T> List<T> scan(final DynamoDbClient dynamoDbClient, final String tableName,
            final Function<Map<String, AttributeValue>, T> fromItem) {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).build())
                .items().stream().map(fromItem).toList();
    }
}
