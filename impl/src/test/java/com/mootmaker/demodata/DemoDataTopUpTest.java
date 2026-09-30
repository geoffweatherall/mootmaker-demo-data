package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.demodata.DemoData.Concerns;
import com.mootmaker.demodata.DemoData.Targets;
import org.junit.jupiter.api.Test;

/**
 * Covers the three top-up concerns' arithmetic and configuration - the parts that are new in the
 * merge, and the ones an environment at or over target depends on for idempotency.
 */
class DemoDataTopUpTest {

  private static final Random FIXED = new Random(42);

  // --- People -------------------------------------------------------------------------

  @Test
  void createsExactlyTheShortfallOfPeople() {
    final FakeGraphQlClient client = new FakeGraphQlClient(12, List.of());

    final int created = DemoData.topUpPeople(client, 40, FIXED);

    assertEquals(28, created);
    assertEquals(28, client.createdPeopleNames().size());
  }

  @Test
  void roughlyNineInTenNewPeopleGetAnAvatar() {
    // As large a batch as the pool can cover, so the 10%-without rate is checked as a rate. FIXED's
    // seed makes the count deterministic, but this asserts its shape rather than pinning the exact
    // number, so it does not become a change-detector on AvatarPool.assign's internals.
    final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of());

    DemoData.topUpPeople(client, 200, FIXED);

    final long withAvatar =
        client.createdPeople().stream().filter(p -> p.avatarSha256() != null).count();
    assertEquals(200, client.createdPeople().size());
    assertTrue(
        withAvatar > 165 && withAvatar < 195, "expected roughly 180 of 200, got " + withAvatar);
  }

  @Test
  void noTwoPeopleCreatedInOneRunShareAnAvatar() {
    final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of());

    DemoData.topUpPeople(client, 200, FIXED);

    final List<String> hashes =
        client.createdPeople().stream()
            .map(FakeGraphQlClient.CreatedPerson::avatarSha256)
            .filter(Objects::nonNull)
            .toList();
    assertEquals(hashes.size(), Set.copyOf(hashes).size(), "an avatar was given to two people");
  }

  /**
   * What is in use is read back from the people who already exist - so it holds across runs, not
   * just within one. 150 of the 200 images are taken; the 50 new people must draw only from the
   * other 50.
   */
  @Test
  void neverAssignsAnAvatarSomeoneInTheEnvironmentAlreadyHas() {
    final Set<String> taken =
        AvatarPool.all().subList(0, 150).stream()
            .map(AvatarPool.Avatar::sha256)
            .collect(Collectors.toSet());
    final FakeGraphQlClient client = new FakeGraphQlClient(150, List.of());
    client.withExistingAvatarHashes(taken);

    DemoData.topUpPeople(client, 200, FIXED);

    assertEquals(50, client.createdPeople().size());
    for (final FakeGraphQlClient.CreatedPerson person : client.createdPeople()) {
      assertFalse(
          taken.contains(person.avatarSha256()), person.name() + " was given an avatar in use");
    }
  }

  @Test
  void runningOutOfAvatarsFailsLoudlyAndCreatesNobody() {
    final FakeGraphQlClient client = new FakeGraphQlClient(AvatarPool.SIZE, List.of());
    client.withExistingAvatarHashes(
        AvatarPool.all().stream().map(AvatarPool.Avatar::sha256).toList());

    // Twenty more people, every image taken. All twenty drawing "no avatar" is 1 in 10^20.
    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> DemoData.topUpPeople(client, AvatarPool.SIZE + 20, FIXED));

    assertTrue(thrown.getMessage().contains("Out of avatars"), thrown.getMessage());
    assertTrue(
        client.createdPeopleNames().isEmpty(),
        "the shortfall must be found before anyone is created, not part-way through");
  }

  /**
   * The point of doing it this way at all: demo-data sets an avatar with exactly the calls a webapp
   * upload feature will make, so it is that feature's standing test.
   */
  @Test
  void anAvatarIsSetByRequestingPuttingAndConfirming() {
    final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of());

    DemoData.topUpPeople(client, 40, FIXED);

    final Map<String, AvatarPool.Avatar> bundledByHash =
        AvatarPool.all().stream()
            .collect(Collectors.toMap(AvatarPool.Avatar::sha256, avatar -> avatar));
    assertFalse(client.uploads().isEmpty());
    for (final FakeGraphQlClient.Upload upload : client.uploads()) {
      assertEquals("image/png", upload.declaredContentType);
      assertEquals("image/png", upload.putContentType);
      assertEquals(upload.declaredContentLength, upload.putBytes.length);
      assertTrue(upload.confirmed, "every upload that was PUT must also be confirmed");
    }
    for (final FakeGraphQlClient.CreatedPerson person : client.createdPeople()) {
      if (person.avatarSha256() != null) {
        assertTrue(
            bundledByHash.containsKey(person.avatarSha256()),
            person.name() + " got bytes that are not a bundled image");
      }
    }
  }

  @Test
  void aRejectedAvatarFailsTheRunRatherThanLeavingSomeoneQuietlyWithoutOne() {
    final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of());
    client.rejectingConfirmWith("NotAnImage");

    final IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> DemoData.topUpPeople(client, 40, FIXED));

    assertTrue(thrown.getMessage().contains("NotAnImage"), thrown.getMessage());
  }

  @Test
  void createsNoPeopleWhenAlreadyAtTarget() {
    final FakeGraphQlClient client = new FakeGraphQlClient(40, List.of());

    assertEquals(0, DemoData.topUpPeople(client, 40, FIXED));
    assertTrue(
        client.createdPeopleNames().isEmpty(), "a run at target must issue no createPerson calls");
  }

  @Test
  void createsNoPeopleWhenOverTarget() {
    // Real sign-ups accumulate on a long-lived environment and are never deleted, so the count
    // legitimately exceeds the target - that must be a no-op, not a negative.
    final FakeGraphQlClient client = new FakeGraphQlClient(63, List.of());

    assertEquals(0, DemoData.topUpPeople(client, 40, FIXED));
    assertTrue(client.createdPeopleNames().isEmpty());
  }

  // --- Rooms --------------------------------------------------------------------------

  @Test
  void createsExactlyTheShortfallOfRooms() {
    final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of("Everest", "Fjord"));

    final int created = DemoData.topUpRooms(client, 10, FIXED);

    assertEquals(8, created);
    assertEquals(8, client.createdRoomNames().size());
  }

  @Test
  void neverReusesAnExistingRoomName() {
    final FakeGraphQlClient client =
        new FakeGraphQlClient(0, List.of("Everest", "Fjord", "Atrium"));

    DemoData.topUpRooms(client, 10, FIXED);

    for (final String created : client.createdRoomNames()) {
      assertFalse(
          List.of("Everest", "Fjord", "Atrium").contains(created),
          "topping up must not create a second room called " + created);
    }
  }

  @Test
  void createsNoRoomsWhenAlreadyAtTarget() {
    final FakeGraphQlClient client = new FakeGraphQlClient(0, SampleData.ROOM_NAMES.subList(0, 10));

    assertEquals(0, DemoData.topUpRooms(client, 10, FIXED));
    assertTrue(client.createdRoomNames().isEmpty());
  }

  @Test
  void suffixesRoomNamesOnceTheCuratedListIsExhausted() {
    final int beyondTheList = SampleData.ROOM_NAMES.size() + 3;

    final List<String> names = DemoData.availableRoomNames(Set.of(), beyondTheList, FIXED);

    assertEquals(beyondTheList, names.size());
    assertEquals(beyondTheList, Set.copyOf(names).size(), "generated room names must be unique");
  }

  @Test
  void suffixedRoomNamesAvoidCollidingWithExistingOnes() {
    // "Everest 2" already taken means the next Everest-derived name has to be "Everest 3".
    final Set<String> used = new HashSet<>(SampleData.ROOM_NAMES);
    used.add("Everest 2");

    final List<String> names = DemoData.availableRoomNames(used, 3, FIXED);

    assertEquals(3, names.size());
    for (final String name : names) {
      assertFalse(used.contains(name), name + " collides with an existing room");
    }
  }

  @Test
  void generatedPersonNamesAreDistinct() {
    // Distinct by construction is what lets topUpPeople index results by position - two
    // coincidentally-identical names would otherwise write both people into one slot.
    final List<String> names = SampleData.personNames(40, FIXED);

    assertEquals(40, names.size());
    assertEquals(40, Set.copyOf(names).size());
  }

  @Test
  void askingForMoreNamesThanExistFailsLoudly() {
    final int impossible = SampleData.FIRST_NAMES.size() * SampleData.LAST_NAMES.size() + 1;

    assertThrows(IllegalArgumentException.class, () -> SampleData.personNames(impossible, FIXED));
  }

  // --- Targets ------------------------------------------------------------------------

  @Test
  void targetsFallBackToDefaultsWhenUnset() {
    final Targets targets = Targets.from(name -> null);

    assertEquals(100, targets.people());
    assertEquals(10, targets.rooms());
    assertEquals(7, targets.daysInPast());
    assertEquals(6, targets.weeksAhead());
  }

  @Test
  void targetsAreReadFromTheEnvironment() {
    final Map<String, String> env =
        Map.of("TARGET_PEOPLE", "5", "TARGET_ROOMS", "2", "DAYS_IN_PAST", "0", "WEEKS_AHEAD", "1");

    final Targets targets = Targets.from(env::get);

    assertEquals(new Targets(5, 2, 0, 1), targets);
  }

  @Test
  void aNonNumericTargetFailsLoudly() {
    assertThrows(IllegalStateException.class, () -> Targets.from(name -> "lots"));
  }

  // --- Concerns -----------------------------------------------------------------------

  @Test
  void everyConcernIsOnByDefault() {
    // The scheduled invocation sends an empty payload, so this is the path that runs daily.
    assertEquals(Concerns.ALL, Concerns.fromPayload(Map.of()));
    assertEquals(Concerns.ALL, Concerns.fromPayload(null));
  }

  @Test
  void aConcernCanBeSwitchedOffPerInvocation() {
    final Concerns concerns = Concerns.fromPayload(Map.of("people", false));

    assertFalse(concerns.people());
    assertTrue(concerns.rooms(), "unmentioned concerns stay on");
    assertTrue(concerns.meetings());
  }

  @Test
  void togglesAcceptStringsFromTheCli() {
    // `aws lambda invoke` payloads are hand-written JSON often enough that "false" arrives as
    // a string; treating that as true would be the worst possible reading of it.
    assertFalse(Concerns.fromPayload(Map.of("meetings", "false")).meetings());
  }

  @Test
  void aNonBooleanToggleFailsLoudly() {
    assertThrows(IllegalArgumentException.class, () -> Concerns.fromPayload(Map.of("rooms", 3)));
  }
}
