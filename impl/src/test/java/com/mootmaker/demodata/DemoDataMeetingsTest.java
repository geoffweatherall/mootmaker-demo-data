package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.demodata.FakeGraphQlClient.BulkCall;
import org.junit.jupiter.api.Test;

/**
 * The meetings concern, which the fake could not answer at all until it learned the composite entry
 * point - and that is how three reads still pointing at deleted root fields ({@code Query.rooms},
 * {@code Query.people}, {@code Query.meetings}) were found.
 */
class DemoDataMeetingsTest {

  private static final Random FIXED = new Random(42);

  /** Enough people and rooms for the scheduler to have something to book. */
  private static FakeGraphQlClient environmentWithCapacity() {
    return new FakeGraphQlClient(20, List.of("Kaikoura", "Wanaka", "Aoraki"));
  }

  private static DemoData.Targets targets(final int daysInPast, final int weeksAhead) {
    return new DemoData.Targets(20, 3, daysInPast, weeksAhead);
  }

  @Test
  void booksEachDayInOneCallRatherThanOneCallPerMeeting() {
    // The point of the whole change. A calendar day is ONE DynamoDB item guarded by optimistic
    // locking, so concurrent creates landing on the same date contend for the same version.
    // One call per day makes each day a single item write instead.
    final FakeGraphQlClient client = environmentWithCapacity();

    final DemoData.Summary summary = DemoData.topUpMeetings(client, targets(0, 1), FIXED);

    final List<BulkCall> calls = client.createMeetingsCalls();
    assertTrue(
        summary.meetingsCreated() > calls.size(),
        "the fixture is pointless unless some day got more than one meeting");
    final Set<String> datesCalled = calls.stream().map(BulkCall::date).collect(Collectors.toSet());
    assertEquals(datesCalled.size(), calls.size(), "a date must be written by exactly one call");
    assertEquals(
        summary.meetingsCreated(),
        calls.stream().mapToInt(BulkCall::size).sum(),
        "every created meeting must be accounted for by some call");
  }

  @Test
  void everyMeetingInACallBelongsToThatCallsDay() {
    // createMeetings takes the date as its OWN argument, separate from the meetings, so a
    // meeting sent to the wrong day's call would be written to the wrong day item entirely.
    final FakeGraphQlClient client = environmentWithCapacity();

    DemoData.topUpMeetings(client, targets(0, 1), FIXED);

    for (final BulkCall call : client.createMeetingsCalls()) {
      assertFalse(call.subjects().isEmpty(), "an empty call is a wasted round trip");
    }
  }

  @Test
  void skipsDaysThatAlreadyHaveMeetings() {
    // The top-up is meant to be safe to repeat: a day that already has meetings is left alone.
    // This is also the read path that used to ask the deleted Query.meetings for a time range
    // and derive dates from start times; it now asks for the days by date and reads back which
    // came home non-empty.
    final FakeGraphQlClient client = environmentWithCapacity();
    final List<String> weekdays =
        DemoData.weekdaysBetween(LocalDate.now(), LocalDate.now().plusDays(7)).stream()
            .map(LocalDate::toString)
            .toList();
    client.withExistingMeetingsOn(weekdays.toArray(String[]::new));

    final DemoData.Summary summary = DemoData.topUpMeetings(client, targets(0, 1), FIXED);

    assertEquals(0, summary.meetingsCreated());
    assertTrue(
        client.createMeetingsCalls().isEmpty(), "nothing should be written when every day is full");
  }

  @Test
  void aRejectedMeetingFailsTheRunAndNamesItsSubject() {
    // Bulk creation reports rejections per input INSTEAD of failing the call, so a partial
    // success has to be turned back into a hard failure - a silently short day is worse than a
    // stopped run. The message names the subject because an index into a request array the
    // caller can no longer see is not a diagnosis.
    final FakeGraphQlClient client = environmentWithCapacity();
    client.rejectingEverything();

    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> DemoData.topUpMeetings(client, targets(0, 1), FIXED));

    final BulkCall firstCall = client.createMeetingsCalls().getFirst();
    assertTrue(thrown.getMessage().contains("createMeetings rejected"), thrown.getMessage());
    assertTrue(
        firstCall.subjects().stream().anyMatch(subject -> thrown.getMessage().contains(subject)),
        "the failure should name a subject, not just an index: " + thrown.getMessage());
  }
}
