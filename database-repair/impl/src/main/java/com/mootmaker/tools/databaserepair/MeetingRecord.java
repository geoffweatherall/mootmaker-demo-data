package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Mirrors just enough of mootmaker-api's MeetingRecord to compute a meeting's expected
 * meeting-participants rows: {@code id}, {@code organiserId}, {@code attendeeIds},
 * {@code startTime}, {@code endTime} - {@code roomId} and {@code subject} aren't needed here.
 * Deliberately duplicated rather than shared - see {@link Person}'s doc comment for why.
 */
record MeetingRecord(String id, String organiserId, List<String> attendeeIds, String startTime, String endTime) {

    Map<String, AttributeValue> toItem() {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("organiserId", AttributeValue.builder().s(organiserId).build());
        item.put("attendeeIds", AttributeValue.builder()
                .l(attendeeIds.stream().map(attendeeId -> AttributeValue.builder().s(attendeeId).build()).toList())
                .build());
        item.put("startTime", AttributeValue.builder().s(startTime).build());
        item.put("endTime", AttributeValue.builder().s(endTime).build());
        return item;
    }

    static MeetingRecord fromItem(final Map<String, AttributeValue> item) {
        final List<String> attendeeIds = item.get("attendeeIds").l().stream().map(AttributeValue::s).toList();
        return new MeetingRecord(
                item.get("id").s(),
                item.get("organiserId").s(),
                attendeeIds,
                item.get("startTime").s(),
                item.get("endTime").s());
    }
}
