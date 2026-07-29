package com.mootmaker.tools.databasereset;

import module java.base;

/**
 * Mirrors just enough of mootmaker-api's MeetingParticipant to identify a meeting-participants row
 * for deletion: {@code personId}, {@code meetingId}, and the {@code sortKey} scheme
 * ({@code startTime + "#" + meetingId}). Deliberately duplicated rather than shared - see
 * {@link MeetingRecord}'s doc comment for why.
 */
record MeetingParticipant(String personId, String meetingId, String startTime) {

    String sortKey() {
        return startTime + "#" + meetingId;
    }

    /** The organiser plus every attendee of the given meeting, i.e. every row it should have. */
    static List<MeetingParticipant> allFor(final MeetingRecord meeting) {
        final List<MeetingParticipant> participants = new ArrayList<>();
        participants.add(new MeetingParticipant(meeting.organiserId(), meeting.id(), meeting.startTime()));
        for (final String attendeeId : meeting.attendeeIds()) {
            participants.add(new MeetingParticipant(attendeeId, meeting.id(), meeting.startTime()));
        }
        return participants;
    }
}
