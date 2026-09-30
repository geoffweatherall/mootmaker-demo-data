package com.mootmaker.demodata;

import module java.base;

/** Curated, meaningful names and subjects used to generate realistic-looking demo data. */
final class SampleData {

  /**
   * First and last names for generated people. A curated list rather than a Faker dependency:
   * datafaker brought ~2 MB plus snakeyaml and guava into the shaded jar for one method call, and
   * this component already keeps its room names and meeting subjects here in exactly this form.
   * {@link #personNames} combines them, so 40x40 gives 1,600 distinct names - far more than any
   * demo environment needs.
   */
  static final List<String> FIRST_NAMES =
      List.of(
          "Amelia",
          "Noah",
          "Priya",
          "Marcus",
          "Sofia",
          "Ethan",
          "Yuki",
          "Olivia",
          "Rajesh",
          "Freya",
          "Idris",
          "Clara",
          "Tomas",
          "Nadia",
          "Hugo",
          "Leila",
          "Callum",
          "Mei",
          "Sebastian",
          "Aisha",
          "Felix",
          "Rosa",
          "Dmitri",
          "Imogen",
          "Kwame",
          "Elena",
          "Jonas",
          "Sana",
          "Oscar",
          "Beatrix",
          "Andres",
          "Niamh",
          "Ravi",
          "Astrid",
          "Theo",
          "Zainab",
          "Lucas",
          "Margot",
          "Emeka",
          "Ingrid");

  /**
   * The subset of {@link #FIRST_NAMES} tagged female, so a generated person is given a photograph
   * of someone whose apparent sex matches their name - see
   * mootmaker/designs/photorealistic-demo-avatars.md. Every other first name in the list is treated
   * as male. A direct tag rather than a name-parsing heuristic: FIRST_NAMES is a small, closed list
   * this class owns end to end, so tagging each of the 40 names once here is strictly more accurate
   * than guessing, and no name in the list is genuinely ambiguous enough to need one. 20 of 40.
   *
   * <p>A guess about a fictional person, made only to keep a demo from looking obviously wrong - it
   * says nothing about anyone real, and nothing but avatar choice reads it.
   */
  static final Set<String> FEMALE_FIRST_NAMES =
      Set.of(
          "Amelia", "Priya", "Sofia", "Olivia", "Freya", "Clara", "Nadia", "Leila", "Mei", "Aisha",
          "Rosa", "Imogen", "Elena", "Sana", "Beatrix", "Niamh", "Astrid", "Zainab", "Margot",
          "Ingrid");

  /** Which half of the photograph pool a person's avatar comes from. */
  enum Sex {
    FEMALE,
    MALE
  }

  /**
   * The sex {@link #FEMALE_FIRST_NAMES} tags this name with. {@code fullName} is expected to be one
   * {@link #personNames} produced, "First Last"; anything else is read by its first word, and an
   * untagged first name is male, as it always has been.
   */
  static Sex sexOf(final String fullName) {
    final String firstName = fullName.trim().split("\\s+", 2)[0];
    return FEMALE_FIRST_NAMES.contains(firstName) ? Sex.FEMALE : Sex.MALE;
  }

  static final List<String> LAST_NAMES =
      List.of(
          "Whitfield",
          "Okonkwo",
          "Lindqvist",
          "Marchetti",
          "Delacroix",
          "Nakamura",
          "Fitzgerald",
          "Vasquez",
          "Abernathy",
          "Kowalski",
          "Bergstrom",
          "Chaudhary",
          "Rosenthal",
          "Mbeki",
          "Castellanos",
          "Thornbury",
          "Halvorsen",
          "Ferreira",
          "Novakova",
          "Aldridge",
          "Sorensen",
          "Petrov",
          "Yamashita",
          "Guerrero",
          "Blackwood",
          "Adeyemi",
          "Lindgren",
          "Sandoval",
          "Ashworth",
          "Dubois",
          "Mikkelsen",
          "Ramanathan",
          "Ellington",
          "Vukovic",
          "Ostrowski",
          "Bellamy",
          "Nakashima",
          "Cavendish",
          "Oyelaran",
          "Strand");

  /**
   * {@code count} distinct full names, drawn in a random order. Every first/last combination is a
   * candidate, so names are unique by construction rather than by retrying on collision - the bug
   * that made sample-data-generator index its people by position instead of by name.
   */
  static List<String> personNames(final int count, final Random random) {
    if (count > FIRST_NAMES.size() * LAST_NAMES.size()) {
      throw new IllegalArgumentException(
          "Cannot generate " + count + " distinct names from the curated lists.");
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
  static final List<String> ROOM_NAMES =
      List.of(
          "Everest",
          "Kilimanjaro",
          "Fjord",
          "Horizon",
          "The Hub",
          "Innovation Lab",
          "Boardroom",
          "Atrium",
          "Sunroom",
          "Skyline Suite",
          "Aurora",
          "Basecamp",
          "Meridian",
          "Compass");

  /** Realistic meeting subjects covering common recurring and one-off meeting types. */
  static final List<String> MEETING_SUBJECTS =
      List.of(
          "Weekly Team Sync",
          "Sprint Planning",
          "Sprint Retrospective",
          "Q3 Budget Review",
          "Client Onboarding Call",
          "1:1 Check-in",
          "Design Review",
          "All-Hands Meeting",
          "Vendor Negotiation",
          "Performance Review",
          "Product Roadmap Review",
          "Marketing Strategy Session",
          "Interview: Senior Engineer",
          "Town Hall",
          "Onboarding Orientation",
          "Architecture Review",
          "Customer Feedback Session",
          "Release Planning",
          "Security Review",
          "Offsite Planning Committee");

  private SampleData() {}
}
