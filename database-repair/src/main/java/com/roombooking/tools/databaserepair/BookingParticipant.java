package com.roombooking.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Mirrors just enough of room-booking-api's BookingParticipant to identify and write a
 * booking-participants row: {@code personId}, {@code bookingId}, {@code startTime},
 * {@code endTime}, and the {@code sortKey} scheme ({@code startTime + "#" + bookingId}).
 * Deliberately duplicated rather than shared - see {@link Person}'s doc comment for why.
 */
record BookingParticipant(String personId, String bookingId, String startTime, String endTime) {

    String sortKey() {
        return startTime + "#" + bookingId;
    }

    Map<String, AttributeValue> toItem() {
        return Map.of(
                "personId", AttributeValue.builder().s(personId).build(),
                "sortKey", AttributeValue.builder().s(sortKey()).build(),
                "bookingId", AttributeValue.builder().s(bookingId).build(),
                "startTime", AttributeValue.builder().s(startTime).build(),
                "endTime", AttributeValue.builder().s(endTime).build());
    }

    static BookingParticipant fromItem(final Map<String, AttributeValue> item) {
        return new BookingParticipant(
                item.get("personId").s(),
                item.get("bookingId").s(),
                item.get("startTime").s(),
                item.get("endTime").s());
    }

    /** The organiser plus every attendee of the given booking, i.e. every row it should have. */
    static List<BookingParticipant> allFor(final BookingRecord booking) {
        final List<BookingParticipant> participants = new ArrayList<>();
        participants.add(new BookingParticipant(booking.organiserId(), booking.id(), booking.startTime(), booking.endTime()));
        for (final String attendeeId : booking.attendeeIds()) {
            participants.add(new BookingParticipant(attendeeId, booking.id(), booking.startTime(), booking.endTime()));
        }
        return participants;
    }
}
