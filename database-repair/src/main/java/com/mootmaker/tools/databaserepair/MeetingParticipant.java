package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Mirrors just enough of mootmaker-api's MeetingParticipant to identify and write a
 * meeting-participants row: {@code personId}, {@code meetingId}, {@code startTime},
 * {@code endTime}, and the {@code sortKey} scheme ({@code startTime + "#" + meetingId}).
 * Deliberately duplicated rather than shared - see {@link Person}'s doc comment for why.
 */
record MeetingParticipant(String personId, String meetingId, String startTime, String endTime) {

    String sortKey() {
        return startTime + "#" + meetingId;
    }

    Map<String, AttributeValue> toItem() {
        return Map.of(
                "personId", AttributeValue.builder().s(personId).build(),
                "sortKey", AttributeValue.builder().s(sortKey()).build(),
                "meetingId", AttributeValue.builder().s(meetingId).build(),
                "startTime", AttributeValue.builder().s(startTime).build(),
                "endTime", AttributeValue.builder().s(endTime).build());
    }

    static MeetingParticipant fromItem(final Map<String, AttributeValue> item) {
        return new MeetingParticipant(
                item.get("personId").s(),
                item.get("meetingId").s(),
                item.get("startTime").s(),
                item.get("endTime").s());
    }

    /** The organiser plus every attendee of the given meeting, i.e. every row it should have. */
    static List<MeetingParticipant> allFor(final MeetingRecord meeting) {
        final List<MeetingParticipant> participants = new ArrayList<>();
        participants.add(new MeetingParticipant(meeting.organiserId(), meeting.id(), meeting.startTime(), meeting.endTime()));
        for (final String attendeeId : meeting.attendeeIds()) {
            participants.add(new MeetingParticipant(attendeeId, meeting.id(), meeting.startTime(), meeting.endTime()));
        }
        return participants;
    }
}
