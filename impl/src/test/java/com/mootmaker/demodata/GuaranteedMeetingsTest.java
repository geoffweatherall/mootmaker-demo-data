package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.demodata.DemoData.MeetingDetail;
import com.mootmaker.demodata.DemoData.RoomDetail;
import org.junit.jupiter.api.Test;

/**
 * Covers the two decisions the guaranteed-meetings concern rests on: which days a person is missing
 * from, and which room can hold a meeting that cannot clash.
 *
 * <p>Every test here asserts the guarantee FIRES on data that needs it, not only that it stays
 * quiet on data that does not - a check that has only ever passed cannot tell you it would catch a
 * demo account with an empty calendar, which is the defect this concern exists for.
 */
class GuaranteedMeetingsTest {

  private static final String DEMO = "demo-person";
  private static final String SOMEONE_ELSE = "other-person";

  private static final LocalDate MON = LocalDate.of(2026, 9, 7);
  private static final LocalDate TUE = LocalDate.of(2026, 9, 8);
  private static final LocalDate WED = LocalDate.of(2026, 9, 9);
  private static final List<LocalDate> WORK_DAYS = List.of(MON, TUE, WED);

  private static MeetingDetail meeting(
      final String room, final String organiser, final String... attendees) {
    return new MeetingDetail(room, organiser, List.of(attendees));
  }

  // --- daysMissingPerson --------------------------------------------------------------

  @Test
  void reportsEveryWorkDayWhenThePersonIsOnNothing() {
    // The exact case that makes the demo account's calendar empty: the day has meetings, so
    // topUpMeetings considers it done, but none of them involve this person.
    final Map<LocalDate, List<MeetingDetail>> byDate =
        Map.of(
            MON, List.of(meeting("room-1", SOMEONE_ELSE, "a", "b")),
            TUE, List.of(meeting("room-2", SOMEONE_ELSE)),
            WED, List.of(meeting("room-1", "a", SOMEONE_ELSE)));

    assertEquals(WORK_DAYS, DemoData.daysMissingPerson(byDate, WORK_DAYS, DEMO));
  }

  @Test
  void countsThePersonAsPresentWhenTheyOrganise() {
    final Map<LocalDate, List<MeetingDetail>> byDate =
        Map.of(MON, List.of(meeting("room-1", DEMO, SOMEONE_ELSE)));

    assertEquals(List.of(TUE, WED), DemoData.daysMissingPerson(byDate, WORK_DAYS, DEMO));
  }

  @Test
  void countsThePersonAsPresentWhenTheyOnlyAttend() {
    // Attending is what the calendar renders, so attending must count - asserting on organiser
    // alone would create a duplicate meeting every run for a person who is merely invited.
    final Map<LocalDate, List<MeetingDetail>> byDate =
        Map.of(MON, List.of(meeting("room-1", SOMEONE_ELSE, "a", DEMO)));

    assertEquals(List.of(TUE, WED), DemoData.daysMissingPerson(byDate, WORK_DAYS, DEMO));
  }

  @Test
  void reportsNothingWhenThePersonIsOnEveryDay() {
    // Idempotency: the second run of an already-guaranteed environment must create nothing.
    final Map<LocalDate, List<MeetingDetail>> byDate =
        Map.of(
            MON, List.of(meeting("room-1", DEMO)),
            TUE, List.of(meeting("room-1", SOMEONE_ELSE, DEMO)),
            WED, List.of(meeting("room-2", DEMO)));

    assertTrue(DemoData.daysMissingPerson(byDate, WORK_DAYS, DEMO).isEmpty());
  }

  @Test
  void treatsADayWithNoEntryAsMissing() {
    assertEquals(WORK_DAYS, DemoData.daysMissingPerson(Map.of(), WORK_DAYS, DEMO));
  }

  // --- pickFreeRoom -------------------------------------------------------------------

  @Test
  void picksARoomWithNoMeetingsThatDay() {
    final List<RoomDetail> rooms =
        List.of(new RoomDetail("room-1", "One", 10), new RoomDetail("room-2", "Two", 10));

    final Optional<RoomDetail> picked =
        DemoData.pickFreeRoom(rooms, List.of(meeting("room-1", SOMEONE_ELSE)));

    assertTrue(picked.isPresent());
    assertEquals("room-2", picked.get().id());
  }

  @Test
  void findsNoRoomWhenEveryRoomIsBookedAtSomePoint() {
    // Free ALL DAY is the rule, not free at a chosen time: the API rejects an overlap with
    // TimeRangeUnavailable, and this is what keeps slot arithmetic out of the concern entirely.
    final List<RoomDetail> rooms =
        List.of(new RoomDetail("room-1", "One", 10), new RoomDetail("room-2", "Two", 10));

    assertFalse(
        DemoData.pickFreeRoom(
                rooms, List.of(meeting("room-1", SOMEONE_ELSE), meeting("room-2", SOMEONE_ELSE)))
            .isPresent());
  }

  @Test
  void skipsAFreeRoomThatCannotHoldBothPeople() {
    // A one-seat room is free and still unusable: the meeting has an organiser and an attendee,
    // and the API rejects the booking with InsufficientCapacity.
    final List<RoomDetail> rooms =
        List.of(new RoomDetail("tiny", "Phone booth", 1), new RoomDetail("room-2", "Two", 4));

    final Optional<RoomDetail> picked = DemoData.pickFreeRoom(rooms, List.of());

    assertTrue(picked.isPresent());
    assertEquals("room-2", picked.get().id());
  }

  @Test
  void findsNoRoomWhenThereAreNoRoomsAtAll() {
    assertFalse(DemoData.pickFreeRoom(List.of(), List.of()).isPresent());
  }

  // --- SsmSecrets.splitIds ------------------------------------------------------------

  @Test
  void parsesTheStringListTerraformWrites() {
    assertEquals(List.of("a", "b"), SsmSecrets.splitIds("a,b"));
    assertEquals(List.of("a", "b"), SsmSecrets.splitIds(" a , b "));
  }

  @Test
  void treatsAnAbsentOrEmptyListAsNoGuarantees() {
    // join() over an empty Terraform list produces "", which must disable the concern rather
    // than produce one guaranteed person with a blank id.
    assertTrue(SsmSecrets.splitIds("").isEmpty());
    assertTrue(SsmSecrets.splitIds("   ").isEmpty());
    assertTrue(SsmSecrets.splitIds(null).isEmpty());
    assertTrue(SsmSecrets.splitIds(",,").isEmpty());
  }
}
