package com.mootmaker.tools.databasereset;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import module java.base;

/**
 * The actual reset logic - moved out of mootmaker-api's GraphQL API (formerly {@code
 * Mutation.reset} / {@code ResetHandler}) and into this standalone tool, so that wiping data needs
 * an explicit, IAM-authenticated Lambda invocation rather than being reachable by any signed-in
 * user of the product. Deletes all stored rooms and meetings, and every person <b>except</b> those
 * linked to a real Cognito account (identified by a non-null {@code cognitoSub}) - so signed-up
 * users never lose the Person record their account is linked to just because someone reset a
 * shared, non-production environment.
 *
 * <p>Each of the three methods below deletes from its own table(s) independently of the others, so
 * {@link DatabaseResetHandler} runs them concurrently; within each one, the individual per-item
 * {@code DeleteItem} calls are themselves spread across {@link DatabaseResetHandler#runInParallel}
 * rather than issued one at a time.
 */
final class DatabaseReset {

    private DatabaseReset() {
    }

    /** Deletes every item in {@code tableName} (used for the Rooms table, which is emptied unconditionally). */
    static int deleteAllItems(final DynamoDbClient dynamoDbClient, final String tableName) {
        final List<Map<String, AttributeValue>> items = scan(dynamoDbClient, tableName);
        DatabaseResetHandler.runInParallel(items, item -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", item.get("id")))
                .build()));
        return items.size();
    }

    /**
     * Deletes every person with no {@code cognitoSub} (guests added directly, or leftover sample
     * data). A person linked to a real Cognito account is their only link back to that account
     * (nothing recreates it after the fact), so it's preserved.
     */
    static int deleteUnlinkedPeople(final DynamoDbClient dynamoDbClient, final String peopleTableName) {
        final List<Map<String, AttributeValue>> unlinked = scan(dynamoDbClient, peopleTableName).stream()
                .filter(item -> !item.containsKey("cognitoSub"))
                .toList();
        DatabaseResetHandler.runInParallel(unlinked, item -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(peopleTableName)
                .key(Map.of("id", item.get("id")))
                .build()));
        return unlinked.size();
    }

    /**
     * meeting-participants is a derived index of the meetings table (see mootmaker-api's {@code
     * MeetingParticipant}), not a source of truth, so every meeting's participant rows are deleted
     * alongside it here - their keys are computed from the meeting item already being read, rather
     * than needing a separate scan of the participants table.
     */
    static int deleteAllMeetingsAndParticipants(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName) {
        final List<MeetingRecord> meetings = scan(dynamoDbClient, meetingsTableName).stream()
                .map(MeetingRecord::fromItem)
                .toList();
        DatabaseResetHandler.runInParallel(meetings, meeting -> {
            for (final MeetingParticipant participant : MeetingParticipant.allFor(meeting)) {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(meetingParticipantsTableName)
                        .key(Map.of(
                                "personId", AttributeValue.builder().s(participant.personId()).build(),
                                "sortKey", AttributeValue.builder().s(participant.sortKey()).build()))
                        .build());
            }
            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(meetingsTableName)
                    .key(Map.of("id", AttributeValue.builder().s(meeting.id()).build()))
                    .build());
        });
        return meetings.size();
    }

    private static List<Map<String, AttributeValue>> scan(final DynamoDbClient dynamoDbClient, final String tableName) {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).build()).items();
    }
}
