package com.mootmaker.demodata;

import module java.base;

/** Curated, meaningful names and subjects used to generate realistic-looking demo data. */
final class SampleData {

    /**
     * First and last names for generated people. A curated list rather than a Faker dependency:
     * datafaker brought ~2 MB plus snakeyaml and guava into the shaded jar for one method call,
     * and this component already keeps its room names and meeting subjects here in exactly this
     * form. {@link #personNames} combines them, so 40x40 gives 1,600 distinct names - far more
     * than any demo environment needs.
     */
    static final List<String> FIRST_NAMES = List.of(
            "Amelia", "Noah", "Priya", "Marcus", "Sofia", "Ethan", "Yuki", "Olivia",
            "Rajesh", "Freya", "Idris", "Clara", "Tomas", "Nadia", "Hugo", "Leila",
            "Callum", "Mei", "Sebastian", "Aisha", "Felix", "Rosa", "Dmitri", "Imogen",
            "Kwame", "Elena", "Jonas", "Sana", "Oscar", "Beatrix", "Andres", "Niamh",
            "Ravi", "Astrid", "Theo", "Zainab", "Lucas", "Margot", "Emeka", "Ingrid");

    static final List<String> LAST_NAMES = List.of(
            "Whitfield", "Okonkwo", "Lindqvist", "Marchetti", "Delacroix", "Nakamura",
            "Fitzgerald", "Vasquez", "Abernathy", "Kowalski", "Bergstrom", "Chaudhary",
            "Rosenthal", "Mbeki", "Castellanos", "Thornbury", "Halvorsen", "Ferreira",
            "Novakova", "Aldridge", "Sorensen", "Petrov", "Yamashita", "Guerrero",
            "Blackwood", "Adeyemi", "Lindgren", "Sandoval", "Ashworth", "Dubois",
            "Mikkelsen", "Ramanathan", "Ellington", "Vukovic", "Ostrowski", "Bellamy",
            "Nakashima", "Cavendish", "Oyelaran", "Strand");

    /**
     * {@code count} distinct full names, drawn in a random order. Every first/last combination is
     * a candidate, so names are unique by construction rather than by retrying on collision - the
     * bug that made sample-data-generator index its people by position instead of by name.
     */
    static List<String> personNames(final int count, final Random random) {
        if (count > FIRST_NAMES.size() * LAST_NAMES.size()) {
            throw new IllegalArgumentException("Cannot generate " + count + " distinct names from the curated lists.");
        }
        final List<String> combinations = new ArrayList<>(FIRST_NAMES.size() * LAST_NAMES.size());
        for (final String first : FIRST_NAMES) {
            for (final String last : LAST_NAMES) {
                combinations.add(first + " " + last);
            }
        }
        Collections.shuffle(combinations, random);
        return List.copyOf(combinations.subList(0, count));
    }


    /** Meeting room names - a mix of geography/nature themes common for real meeting rooms. */
    static final List<String> ROOM_NAMES = List.of(
            "Everest", "Kilimanjaro", "Fjord", "Horizon", "The Hub", "Innovation Lab",
            "Boardroom", "Atrium", "Sunroom", "Skyline Suite", "Aurora", "Basecamp",
            "Meridian", "Compass");


    /** Realistic meeting subjects covering common recurring and one-off meeting types. */
    static final List<String> MEETING_SUBJECTS = List.of(
            "Weekly Team Sync", "Sprint Planning", "Sprint Retrospective", "Q3 Budget Review",
            "Client Onboarding Call", "1:1 Check-in", "Design Review", "All-Hands Meeting",
            "Vendor Negotiation", "Performance Review", "Product Roadmap Review",
            "Marketing Strategy Session", "Interview: Senior Engineer", "Town Hall",
            "Onboarding Orientation", "Architecture Review", "Customer Feedback Session",
            "Release Planning", "Security Review", "Offsite Planning Committee");

    private SampleData() {
    }
}
