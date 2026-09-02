package com.mootmaker.demodata;

import com.mootmaker.demodata.DemoData.Concerns;
import com.mootmaker.demodata.DemoData.Targets;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void createsNoPeopleWhenAlreadyAtTarget() {
        final FakeGraphQlClient client = new FakeGraphQlClient(40, List.of());

        assertEquals(0, DemoData.topUpPeople(client, 40, FIXED));
        assertTrue(client.createdPeopleNames().isEmpty(), "a run at target must issue no createPerson calls");
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
        final FakeGraphQlClient client = new FakeGraphQlClient(0, List.of("Everest", "Fjord", "Atrium"));

        DemoData.topUpRooms(client, 10, FIXED);

        for (final String created : client.createdRoomNames()) {
            assertFalse(List.of("Everest", "Fjord", "Atrium").contains(created),
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

        assertEquals(40, targets.people());
        assertEquals(10, targets.rooms());
        assertEquals(7, targets.daysInPast());
        assertEquals(6, targets.weeksAhead());
    }

    @Test
    void targetsAreReadFromTheEnvironment() {
        final Map<String, String> env = Map.of(
                "TARGET_PEOPLE", "5", "TARGET_ROOMS", "2", "DAYS_IN_PAST", "0", "WEEKS_AHEAD", "1");

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
