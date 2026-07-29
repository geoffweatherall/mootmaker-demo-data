package com.mootmaker.tools.databasereset;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseResetTest {

    private static final String ROOMS_TABLE = "Rooms";
    private static final String PEOPLE_TABLE = "People";
    private static final String MEETINGS_TABLE = "Meetings";
    private static final String PARTICIPANTS_TABLE = "MeetingParticipants";

    private static Map<String, AttributeValue> room(final String id) {
        return Map.of("id", AttributeValue.builder().s(id).build());
    }

    private static Map<String, AttributeValue> person(final String id, final String cognitoSub) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("name", AttributeValue.builder().s("Someone").build());
        if (cognitoSub != null) {
            item.put("cognitoSub", AttributeValue.builder().s(cognitoSub).build());
        }
        return item;
    }

    private static MeetingRecord meeting(final String id, final String organiserId, final List<String> attendeeIds) {
        return new MeetingRecord(id, organiserId, attendeeIds, "2026-07-01T09:00:00", "2026-07-01T10:00:00");
    }

    @Test
    void deleteAllItemsEmptiesTheTable() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(ROOMS_TABLE, new ArrayList<>(List.of(room("room-1"), room("room-2"))));

        final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

        assertEquals(2, deleted);
        assertTrue(dynamoDbClient.tables.get(ROOMS_TABLE).isEmpty());
    }

    @Test
    void deleteAllItemsSucceedsWhenTableIsAlreadyEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

        assertEquals(0, deleted);
    }

    @Test
    void deleteUnlinkedPeopleRemovesOnlyPeopleWithNoCognitoSub() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(
                person("guest-1", null),
                person("linked-1", "cognito-sub-123"))));

        final int deleted = DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, PEOPLE_TABLE);

        assertEquals(1, deleted);
        final List<Map<String, AttributeValue>> remaining = dynamoDbClient.tables.get(PEOPLE_TABLE);
        assertEquals(1, remaining.size());
        assertEquals("linked-1", remaining.getFirst().get("id").s());
    }

    @Test
    void deleteAllMeetingsAndParticipantsRemovesEveryMeetingAndItsParticipantRows() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final MeetingRecord meeting = meeting("meeting-1", "organiser-1", List.of("attendee-1"));
        dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(List.of(meeting.toItem())));
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(
                MeetingParticipant.allFor(meeting).stream()
                        .map(participant -> Map.of(
                                "personId", AttributeValue.builder().s(participant.personId()).build(),
                                "sortKey", AttributeValue.builder().s(participant.sortKey()).build()))
                        .map(item -> (Map<String, AttributeValue>) item)
                        .toList()));

        final int deleted = DatabaseReset.deleteAllMeetingsAndParticipants(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE);

        assertEquals(1, deleted);
        assertTrue(dynamoDbClient.tables.get(MEETINGS_TABLE).isEmpty());
        assertTrue(dynamoDbClient.tables.get(PARTICIPANTS_TABLE).isEmpty());
    }

    @Test
    void deleteAllMeetingsAndParticipantsSucceedsWhenTablesAreAlreadyEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final int deleted = DatabaseReset.deleteAllMeetingsAndParticipants(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE);

        assertEquals(0, deleted);
    }
}
