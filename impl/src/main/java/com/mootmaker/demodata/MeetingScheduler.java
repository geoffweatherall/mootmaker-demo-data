package com.mootmaker.demodata;

import module java.base;

/**
 * Pure scheduling logic (no network calls) for generating a realistic-looking set of meetings
 * across a set of rooms for a specific list of business days.
 *
 * <p>Each room is scheduled independently against a shared, bimodal time-of-day density (busy from
 * 09:00, quiet over lunch, busy again after lunch tailing off toward 17:00) rather than a flat
 * random start - see {@link #densityWeight}. Each room also has a fixed "reputation" - a target
 * occupancy tier derived deterministically from its id (see {@link #tierFor}) - so some rooms stay
 * consistently in higher demand than others across the whole generated calendar, not just on
 * whichever day happens to roll a high number.
 *
 * <p>Rooms are scheduled independently, so meetings in different rooms may legitimately overlap in
 * time. Person conflicts are modelled the way real calendars work rather than as a blanket "nobody
 * double-booked, ever" rule: an <b>organiser</b> is never double-booked, as organiser or attendee,
 * at an overlapping time - running two meetings at once isn't something an RSVP can resolve. An
 * <b>attendee</b> can be invited into up to {@link #MAX_CONCURRENT_ATTENDEE_INVITES} overlapping
 * meetings, since a real conflicting invite gets resolved via RSVP rather than never existing in
 * the first place - see {@link #resolveAttendeeStatuses} for how those invites' statuses get
 * assigned.
 *
 * <p>Takes an explicit {@code List<LocalDate>} of days to generate for, rather than a contiguous
 * day-offset range: {@link DemoData} has already worked out exactly which business days are empty
 * and need topping up, and those need not be contiguous.
 */
final class MeetingScheduler {

  /** Meeting durations to vary between, all multiples of 15 minutes (the API's boundary rule). */
  private static final List<Integer> DURATION_MINUTES_OPTIONS = List.of(15, 30, 45, 60, 90, 120);

  private static final int BUSINESS_DAY_START_HOUR = 8;
  private static final int BUSINESS_DAY_END_HOUR = 17;
  private static final int BUSINESS_DAY_MINUTES =
      (BUSINESS_DAY_END_HOUR - BUSINESS_DAY_START_HOUR) * 60;

  /**
   * Safety valve on how many meetings a single room can get in one day. Not a target - the
   * candidate-slot pool (see {@link #generateForRoomDay}) naturally bounds this well below the
   * theoretical 36 (every 15-minute slot in the business day) - but a cap keeps a pathological case
   * (a room's occupancy target reached via a run of very short meetings) from looking absurd.
   */
  private static final int MAX_MEETINGS_PER_ROOM_PER_DAY = 10;

  /** Step used to align every candidate start time, matching the API's 15-minute boundary rule. */
  private static final int RETRY_STEP_MINUTES = 15;

  /**
   * Chance that a participant is given a real gap before they can be booked into another meeting,
   * rather than being immediately available the instant this meeting ends. A per-person
   * availability construct only - it affects when that person can next be booked, not when a room's
   * own next meeting can start.
   */
  private static final double GAP_AFTER_MEETING_PROBABILITY = 0.65;

  /** How long that post-meeting gap lasts, when one is applied. */
  private static final List<Integer> GAP_MINUTES_OPTIONS = List.of(15, 30, 45, 60);

  /** Every meeting needs an organiser plus at least one attendee. */
  private static final int MIN_PARTICIPANTS = 2;

  /**
   * How many overlapping meetings a single attendee can be invited into at once. An organiser is
   * never part of this - see the class doc comment - so this only ever bounds attendee invites.
   */
  private static final int MAX_CONCURRENT_ATTENDEE_INVITES = 2;

  /**
   * Chance that a meeting is deliberately sized near a room's full capacity, for visual UI testing
   * with a realistic "room nearly full" case, rather than a small/medium catch-up.
   */
  private static final double NEAR_CAPACITY_MEETING_PROBABILITY = 0.20;

  /** A near-capacity meeting uses at least this fraction of the room's capacity. */
  private static final double NEAR_CAPACITY_MINIMUM_FRACTION = 0.8;

  /**
   * Weighted attendee-count outcomes for a small/medium (non near-capacity) meeting, before capping
   * to what the free-people pool allows: one attendee besides the organiser is the most common
   * case, tapering off from there.
   */
  private static final double[] SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS = {0.55, 0.85, 1.0};

  /**
   * Per Geoff's explicit mix (designs/attendee-response-status.md): roughly 60% {@code Going}, the
   * remaining ~40% split randomly and roughly evenly across {@code NotGoing}/{@code Maybe}/{@code
   * NoResponse}. Applies to every attendee invite that isn't part of a genuinely overlapping pair -
   * see {@link #resolveAttendeeStatuses}.
   */
  private static final double[] ATTENDEE_STATUS_CUMULATIVE_WEIGHTS = {
    0.60, 0.60 + (0.40 / 3), 0.60 + (0.40 / 3) * 2, 1.0
  };

  private static final List<String> ATTENDEE_STATUS_OPTIONS =
      List.of("Going", "NotGoing", "Maybe", "NoResponse");

  /** The three non-Going statuses, equally weighted - see {@link #resolveAttendeeStatuses}. */
  private static final List<String> NON_GOING_STATUS_OPTIONS =
      List.of("NotGoing", "Maybe", "NoResponse");

  /**
   * Chance that a genuinely overlapping pair (or chain) of a person's attendee invites resolves the
   * way a real calendar mostly does: exactly one {@code Going}, the rest not. The remaining share
   * is the deliberate "over-promised, double-booked, failed to meet an expectation" mistake,
   * modelled as every invite in the conflict resolving to {@code Going} - see
   * designs/realistic-demo-meeting- schedule.md's "Trade-offs and decisions" #1.
   */
  private static final double RESOLVED_CORRECTLY_PROBABILITY = 0.95;

  /**
   * Time-of-day density control points as (hour-of-day, relative weight), linearly interpolated
   * between - see {@link #densityWeight}. Two peaks (~10:00 and ~14:00), a non-zero floor through
   * lunch (~12:30, about 8% of peak - a small chance of a lunch meeting rather than a hard cutoff),
   * light at the very start and end of the business day.
   */
  private static final double[][] DENSITY_CONTROL_POINTS_HOUR_WEIGHT = {
    {8.0, 0.15},
    {9.0, 0.55},
    {10.0, 1.0},
    {10.5, 1.0},
    {11.5, 0.55},
    {12.0, 0.2},
    {12.5, 0.08},
    {13.0, 0.2},
    {13.5, 0.55},
    {14.0, 0.95},
    {14.5, 1.0},
    {15.5, 0.55},
    {16.0, 0.25},
    {17.0, 0.05},
  };

  /**
   * A room's fixed "reputation" - how much of the business day it targets being occupied, on a
   * given day. Assigned deterministically per room (see {@link #tierFor}), not re-rolled daily, so
   * certain rooms stay consistently busier than others across the whole generated calendar.
   */
  private enum RoomTier {
    HIGH_DEMAND(0.55, 0.60),
    TYPICAL(0.25, 0.35),
    QUIET(0.10, 0.15);

    final double minOccupancyFraction;
    final double maxOccupancyFraction;

    RoomTier(final double minOccupancyFraction, final double maxOccupancyFraction) {
      this.minOccupancyFraction = minOccupancyFraction;
      this.maxOccupancyFraction = maxOccupancyFraction;
    }
  }

  record RoomInfo(String id, int capacity) {}

  record GeneratedMeeting(
      String roomId,
      String subject,
      String organiserId,
      List<String> attendeeIds,
      List<String> attendeeStatuses,
      LocalDateTime startTime,
      LocalDateTime endTime) {}

  /** A placed meeting before RSVP statuses are resolved - see {@link #resolveAttendeeStatuses}. */
  private record PlacedMeeting(
      String roomId,
      String subject,
      String organiserId,
      List<String> attendeeIds,
      LocalDateTime startTime,
      LocalDateTime endTime) {}

  /**
   * A person's (or room's) busy time range; touching end-to-start is not an overlap, matching the
   * API's own rule.
   */
  private record Interval(LocalDateTime start, LocalDateTime end) {
    boolean overlaps(final LocalDateTime otherStart, final LocalDateTime otherEnd) {
      return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
  }

  private MeetingScheduler() {}

  /**
   * Generates meetings for every room, for every day in {@code targetDays} (assumed already
   * filtered to weekdays with no existing meetings - this method doesn't check either).
   *
   * <p>Placement is structural first (which room, which time, who), then a separate pass resolves
   * every attendee invite's RSVP status (see {@link #resolveAttendeeStatuses}) - the first meeting
   * of a genuinely overlapping pair is placed before its overlap partner exists, so status can't be
   * decided inline the way organiser/attendee selection can.
   */
  static List<GeneratedMeeting> generate(
      final List<RoomInfo> rooms,
      final List<String> personIds,
      final List<LocalDate> targetDays,
      final Random random) {
    final List<PlacedMeeting> placed = new ArrayList<>();
    final Map<String, List<Interval>> organiserBusyByPerson = new HashMap<>();
    final Map<String, List<Interval>> attendeeBusyByPerson = new HashMap<>();

    for (final LocalDate day : targetDays) {
      for (final RoomInfo room : rooms) {
        placed.addAll(
            generateForRoomDay(
                room, day, personIds, organiserBusyByPerson, attendeeBusyByPerson, random));
      }
    }
    return resolveAttendeeStatuses(placed, random);
  }

  /**
   * A room's fixed occupancy tier, derived deterministically from its id via {@code
   * String.hashCode()} - specified and stable by the String contract (unlike {@code
   * Object.hashCode()}), so the same room always lands in the same tier across runs. Roughly 20% of
   * rooms are {@code HIGH_DEMAND}, 50% {@code TYPICAL}, 30% {@code QUIET}.
   */
  private static RoomTier tierFor(final String roomId) {
    final int bucket = Math.floorMod(roomId.hashCode(), 100);
    if (bucket < 20) {
      return RoomTier.HIGH_DEMAND;
    }
    if (bucket < 70) {
      return RoomTier.TYPICAL;
    }
    return RoomTier.QUIET;
  }

  /**
   * Fills one room's one day: repeatedly draws a candidate start time from the remaining pool of
   * 15-minute-aligned slots, weighted by {@link #densityWeight}, and places a meeting there if the
   * room isn't already booked over that time and enough people are available; keeps going until the
   * day's occupancy target (from the room's tier, see {@link #tierFor}) is met, the safety cap is
   * hit, or the candidate pool is exhausted. A failed candidate is dropped from the pool so it is
   * never retried; a successful placement also drops every slot the new meeting now overlaps, so
   * the room is never double-booked against itself.
   */
  private static List<PlacedMeeting> generateForRoomDay(
      final RoomInfo room,
      final LocalDate day,
      final List<String> personIds,
      final Map<String, List<Interval>> organiserBusyByPerson,
      final Map<String, List<Interval>> attendeeBusyByPerson,
      final Random random) {
    final List<PlacedMeeting> roomDayMeetings = new ArrayList<>();
    final LocalDateTime dayStart = day.atTime(BUSINESS_DAY_START_HOUR, 0);
    final LocalDateTime dayEnd = day.atTime(BUSINESS_DAY_END_HOUR, 0);

    final RoomTier tier = tierFor(room.id());
    final double occupancyFraction =
        tier.minOccupancyFraction
            + random.nextDouble() * (tier.maxOccupancyFraction - tier.minOccupancyFraction);
    final long targetOccupiedMinutes = Math.round(BUSINESS_DAY_MINUTES * occupancyFraction);

    final List<LocalDateTime> candidatePool = new ArrayList<>();
    for (int step = 0; step * RETRY_STEP_MINUTES < BUSINESS_DAY_MINUTES; step++) {
      candidatePool.add(dayStart.plusMinutes((long) step * RETRY_STEP_MINUTES));
    }

    long occupiedMinutes = 0;
    while (!candidatePool.isEmpty()
        && occupiedMinutes < targetOccupiedMinutes
        && roomDayMeetings.size() < MAX_MEETINGS_PER_ROOM_PER_DAY) {
      final LocalDateTime candidateStart = drawWeightedCandidate(candidatePool, random);

      final PlacedMeeting meeting =
          tryPlaceMeeting(
              room,
              candidateStart,
              dayEnd,
              roomDayMeetings,
              personIds,
              organiserBusyByPerson,
              attendeeBusyByPerson,
              random);

      if (meeting == null) {
        candidatePool.remove(candidateStart);
        continue;
      }
      roomDayMeetings.add(meeting);
      occupiedMinutes += Duration.between(meeting.startTime(), meeting.endTime()).toMinutes();
      candidatePool.removeIf(
          slot -> {
            final LocalDateTime slotEnd = slot.plusMinutes(RETRY_STEP_MINUTES);
            return meeting.startTime().isBefore(slotEnd) && slot.isBefore(meeting.endTime());
          });
    }
    return roomDayMeetings;
  }

  /**
   * Picks one candidate from the pool, weighted by {@link #densityWeight} - standard weighted
   * sampling by cumulative weight, not rejection sampling, since the pool is small (at most 36
   * entries) and shrinks as slots are consumed or ruled out.
   */
  private static LocalDateTime drawWeightedCandidate(
      final List<LocalDateTime> pool, final Random random) {
    final double[] weights = new double[pool.size()];
    double totalWeight = 0;
    for (int i = 0; i < pool.size(); i++) {
      weights[i] = densityWeight(pool.get(i));
      totalWeight += weights[i];
    }
    double roll = random.nextDouble() * totalWeight;
    for (int i = 0; i < pool.size(); i++) {
      roll -= weights[i];
      if (roll < 0) {
        return pool.get(i);
      }
    }
    return pool.getLast();
  }

  /** Linear interpolation over {@link #DENSITY_CONTROL_POINTS_HOUR_WEIGHT}. */
  private static double densityWeight(final LocalDateTime time) {
    final double hour = time.getHour() + time.getMinute() / 60.0;
    final double[][] points = DENSITY_CONTROL_POINTS_HOUR_WEIGHT;
    if (hour <= points[0][0]) {
      return points[0][1];
    }
    for (int i = 1; i < points.length; i++) {
      if (hour <= points[i][0]) {
        final double x0 = points[i - 1][0];
        final double y0 = points[i - 1][1];
        final double x1 = points[i][0];
        final double y1 = points[i][1];
        final double t = (hour - x0) / (x1 - x0);
        return y0 + t * (y1 - y0);
      }
    }
    return points[points.length - 1][1];
  }

  /**
   * Attempts to place one meeting starting at {@code candidateStart}: picks a duration that fits
   * before {@code dayEnd} and doesn't overlap this room's already-placed meetings today, then
   * checks enough people are available under the relaxed conflict rule (see the class doc comment).
   * Returns {@code null} if no duration works, either on the room or on people.
   */
  private static PlacedMeeting tryPlaceMeeting(
      final RoomInfo room,
      final LocalDateTime candidateStart,
      final LocalDateTime dayEnd,
      final List<PlacedMeeting> roomDayMeetings,
      final List<String> personIds,
      final Map<String, List<Interval>> organiserBusyByPerson,
      final Map<String, List<Interval>> attendeeBusyByPerson,
      final Random random) {
    final List<Integer> feasibleDurations = new ArrayList<>(DURATION_MINUTES_OPTIONS);
    Collections.shuffle(feasibleDurations, random);

    for (final int durationMinutes : feasibleDurations) {
      final LocalDateTime endTime = candidateStart.plusMinutes(durationMinutes);
      if (endTime.isAfter(dayEnd)) {
        continue;
      }
      final boolean overlapsRoom =
          roomDayMeetings.stream()
              .anyMatch(
                  existing ->
                      candidateStart.isBefore(existing.endTime())
                          && existing.startTime().isBefore(endTime));
      if (overlapsRoom) {
        continue;
      }

      final PlacedMeeting meeting =
          tryAssignParticipants(
              room,
              candidateStart,
              endTime,
              personIds,
              organiserBusyByPerson,
              attendeeBusyByPerson,
              random);
      if (meeting != null) {
        return meeting;
      }
    }
    return null;
  }

  /**
   * Given a room and a fixed [start, end) already cleared of room-self-overlap, tries to find an
   * organiser and enough attendees. The organiser must be free of every other booking, as organiser
   * or attendee (see the class doc comment); attendees only need to be free of any organiser
   * booking, and under {@link #MAX_CONCURRENT_ATTENDEE_INVITES} concurrent attendee bookings of
   * their own.
   */
  private static PlacedMeeting tryAssignParticipants(
      final RoomInfo room,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final List<String> personIds,
      final Map<String, List<Interval>> organiserBusyByPerson,
      final Map<String, List<Interval>> attendeeBusyByPerson,
      final Random random) {
    final List<String> organiserCandidates = new ArrayList<>(personIds);
    Collections.shuffle(organiserCandidates, random);
    organiserCandidates.removeIf(
        personId ->
            isBusy(organiserBusyByPerson, personId, startTime, endTime)
                || isBusy(attendeeBusyByPerson, personId, startTime, endTime));
    if (organiserCandidates.isEmpty()) {
      return null;
    }
    final String organiserId = organiserCandidates.getFirst();

    final List<String> attendeeCandidates = new ArrayList<>(personIds);
    attendeeCandidates.remove(organiserId);
    Collections.shuffle(attendeeCandidates, random);
    attendeeCandidates.removeIf(
        personId ->
            isBusy(organiserBusyByPerson, personId, startTime, endTime)
                || overlapCount(attendeeBusyByPerson, personId, startTime, endTime)
                    >= MAX_CONCURRENT_ATTENDEE_INVITES);
    if (attendeeCandidates.isEmpty()) {
      return null;
    }

    final boolean scheduleNearCapacity = random.nextDouble() < NEAR_CAPACITY_MEETING_PROBABILITY;
    final int desiredParticipants =
        scheduleNearCapacity
            ? nearCapacityParticipantCount(room.capacity(), random)
            : Math.max(MIN_PARTICIPANTS, 1 + pickSmallMeetingAttendeeCount(random));
    final int totalParticipants =
        Math.min(desiredParticipants, Math.min(room.capacity(), attendeeCandidates.size() + 1));
    final int attendeeCount = totalParticipants - 1;

    final List<String> attendeeIds = List.copyOf(attendeeCandidates.subList(0, attendeeCount));

    markBusyWithOptionalGap(organiserBusyByPerson, organiserId, startTime, endTime, random);
    for (final String attendeeId : attendeeIds) {
      markBusyWithOptionalGap(attendeeBusyByPerson, attendeeId, startTime, endTime, random);
    }

    final String subject =
        SampleData.MEETING_SUBJECTS.get(random.nextInt(SampleData.MEETING_SUBJECTS.size()));
    return new PlacedMeeting(room.id(), subject, organiserId, attendeeIds, startTime, endTime);
  }

  /**
   * A near-capacity meeting's total headcount: a random value between {@link
   * #NEAR_CAPACITY_MINIMUM_FRACTION} of the room's capacity and full capacity, inclusive.
   */
  private static int nearCapacityParticipantCount(final int capacity, final Random random) {
    final int low =
        Math.max(MIN_PARTICIPANTS, (int) Math.ceil(capacity * NEAR_CAPACITY_MINIMUM_FRACTION));
    if (low >= capacity) {
      return capacity;
    }
    return low + random.nextInt(capacity - low + 1);
  }

  /**
   * Rolls a weighted attendee count for a small/medium meeting (see {@link
   * #SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS}); always at least 1.
   */
  private static int pickSmallMeetingAttendeeCount(final Random random) {
    final double roll = random.nextDouble();
    for (int i = 0; i < SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS.length; i++) {
      if (roll < SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS[i]) {
        return i + 1;
      }
    }
    return SMALL_MEETING_ATTENDEE_COUNT_CUMULATIVE_WEIGHTS.length;
  }

  private static boolean isBusy(
      final Map<String, List<Interval>> busyByPerson,
      final String personId,
      final LocalDateTime start,
      final LocalDateTime end) {
    return busyByPerson.getOrDefault(personId, List.of()).stream()
        .anyMatch(interval -> interval.overlaps(start, end));
  }

  private static long overlapCount(
      final Map<String, List<Interval>> busyByPerson,
      final String personId,
      final LocalDateTime start,
      final LocalDateTime end) {
    return busyByPerson.getOrDefault(personId, List.of()).stream()
        .filter(interval -> interval.overlaps(start, end))
        .count();
  }

  /**
   * Marks a participant busy for the meeting's real duration and, most of the time, for a further
   * random gap afterwards - so they're not picked for another meeting the instant this one ends.
   * The gap only affects when this person can next be scheduled; the meeting's actual start/end
   * times are unaffected.
   */
  private static void markBusyWithOptionalGap(
      final Map<String, List<Interval>> busyByPerson,
      final String personId,
      final LocalDateTime start,
      final LocalDateTime end,
      final Random random) {
    LocalDateTime busyUntil = end;
    if (random.nextDouble() < GAP_AFTER_MEETING_PROBABILITY) {
      final int gapMinutes = GAP_MINUTES_OPTIONS.get(random.nextInt(GAP_MINUTES_OPTIONS.size()));
      busyUntil = end.plusMinutes(gapMinutes);
    }
    busyByPerson
        .computeIfAbsent(personId, key -> new ArrayList<>())
        .add(new Interval(start, busyUntil));
  }

  /**
   * Resolves every attendee invite's RSVP status and produces the final {@link GeneratedMeeting}
   * list. For each person, their attendee invites are grouped into connected components by time
   * overlap (a standard sweep: sort by start, extend the current group while the next invite starts
   * before the group's running max end - this correctly captures a transitive chain, not just
   * directly-overlapping pairs). A component of size 1 draws independently from the usual mix (see
   * {@link #ATTENDEE_STATUS_CUMULATIVE_WEIGHTS}); a component of size 2 or more - only possible
   * because of the relaxed attendee-conflict rule - resolves via {@link
   * #RESOLVED_CORRECTLY_PROBABILITY}: most of the time exactly one invite in the group is {@code
   * Going} and the rest are drawn from the other three statuses, the rest of the time every invite
   * in the group is {@code Going} (the deliberate over-commitment mistake).
   */
  private static List<GeneratedMeeting> resolveAttendeeStatuses(
      final List<PlacedMeeting> placed, final Random random) {
    final Map<String, List<Integer>> attendeeInvitesByPerson = new HashMap<>();
    for (int i = 0; i < placed.size(); i++) {
      for (final String attendeeId : placed.get(i).attendeeIds()) {
        attendeeInvitesByPerson.computeIfAbsent(attendeeId, key -> new ArrayList<>()).add(i);
      }
    }

    final Map<Integer, Map<String, String>> statusByMeetingAndPerson = new HashMap<>();
    for (final Map.Entry<String, List<Integer>> entry : attendeeInvitesByPerson.entrySet()) {
      final String personId = entry.getKey();
      for (final List<Integer> cluster : overlapClusters(entry.getValue(), placed)) {
        assignClusterStatuses(cluster, personId, statusByMeetingAndPerson, random);
      }
    }

    final List<GeneratedMeeting> meetings = new ArrayList<>(placed.size());
    for (int i = 0; i < placed.size(); i++) {
      final PlacedMeeting meeting = placed.get(i);
      final Map<String, String> statusByPerson = statusByMeetingAndPerson.getOrDefault(i, Map.of());
      final List<String> attendeeStatuses =
          meeting.attendeeIds().stream().map(statusByPerson::get).toList();
      meetings.add(
          new GeneratedMeeting(
              meeting.roomId(),
              meeting.subject(),
              meeting.organiserId(),
              meeting.attendeeIds(),
              attendeeStatuses,
              meeting.startTime(),
              meeting.endTime()));
    }
    return meetings;
  }

  /**
   * Groups one person's attendee-invite indices into overlap-connected components, sorted by start
   * time. See {@link #resolveAttendeeStatuses} for why a connected component, not just pairs.
   */
  private static List<List<Integer>> overlapClusters(
      final List<Integer> meetingIndices, final List<PlacedMeeting> placed) {
    final List<Integer> sorted =
        meetingIndices.stream()
            .sorted(Comparator.comparing(i -> placed.get(i).startTime()))
            .toList();

    final List<List<Integer>> clusters = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    LocalDateTime currentMaxEnd = null;
    for (final int index : sorted) {
      final LocalDateTime start = placed.get(index).startTime();
      final LocalDateTime end = placed.get(index).endTime();
      if (current.isEmpty() || start.isBefore(currentMaxEnd)) {
        current.add(index);
        currentMaxEnd = currentMaxEnd == null || end.isAfter(currentMaxEnd) ? end : currentMaxEnd;
      } else {
        clusters.add(current);
        current = new ArrayList<>(List.of(index));
        currentMaxEnd = end;
      }
    }
    if (!current.isEmpty()) {
      clusters.add(current);
    }
    return clusters;
  }

  private static void assignClusterStatuses(
      final List<Integer> cluster,
      final String personId,
      final Map<Integer, Map<String, String>> statusByMeetingAndPerson,
      final Random random) {
    if (cluster.size() == 1) {
      putStatus(statusByMeetingAndPerson, cluster.getFirst(), personId, pickAttendeeStatus(random));
      return;
    }

    if (random.nextDouble() < RESOLVED_CORRECTLY_PROBABILITY) {
      final int goingIndex = cluster.get(random.nextInt(cluster.size()));
      for (final int meetingIndex : cluster) {
        final String status = meetingIndex == goingIndex ? "Going" : pickNonGoingStatus(random);
        putStatus(statusByMeetingAndPerson, meetingIndex, personId, status);
      }
    } else {
      for (final int meetingIndex : cluster) {
        putStatus(statusByMeetingAndPerson, meetingIndex, personId, "Going");
      }
    }
  }

  private static void putStatus(
      final Map<Integer, Map<String, String>> statusByMeetingAndPerson,
      final int meetingIndex,
      final String personId,
      final String status) {
    statusByMeetingAndPerson
        .computeIfAbsent(meetingIndex, key -> new HashMap<>())
        .put(personId, status);
  }

  /**
   * Rolls a weighted attendee status (see {@link #ATTENDEE_STATUS_CUMULATIVE_WEIGHTS}) - roughly
   * 60% {@code Going}, the rest split evenly across the other three. Used only for an invite that
   * isn't part of a genuinely overlapping pair - see {@link #resolveAttendeeStatuses}.
   */
  private static String pickAttendeeStatus(final Random random) {
    final double roll = random.nextDouble();
    for (int i = 0; i < ATTENDEE_STATUS_CUMULATIVE_WEIGHTS.length; i++) {
      if (roll < ATTENDEE_STATUS_CUMULATIVE_WEIGHTS[i]) {
        return ATTENDEE_STATUS_OPTIONS.get(i);
      }
    }
    return ATTENDEE_STATUS_OPTIONS.getLast();
  }

  /** Uniformly picks one of the three non-Going statuses - see {@link #resolveAttendeeStatuses}. */
  private static String pickNonGoingStatus(final Random random) {
    return NON_GOING_STATUS_OPTIONS.get(random.nextInt(NON_GOING_STATUS_OPTIONS.size()));
  }
}
