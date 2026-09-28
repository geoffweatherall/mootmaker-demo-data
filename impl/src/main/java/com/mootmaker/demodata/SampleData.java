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
   * The subset of {@link #FIRST_NAMES} tagged female, so {@link #avatarPhotoFor} can pick a
   * gender-matched stock photo - see designs/person-avatar-photos.md. Every other first name in the
   * list is treated as male. A direct tag rather than a name-parsing heuristic: FIRST_NAMES is a
   * small, closed list this class already owns end to end, so tagging each of the 40 names once
   * here is strictly more accurate than guessing, and no name in the list is genuinely ambiguous
   * enough to need one. 20 of 40.
   */
  static final Set<String> FEMALE_FIRST_NAMES =
      Set.of(
          "Amelia", "Priya", "Sofia", "Olivia", "Freya", "Clara", "Nadia", "Leila", "Mei", "Aisha",
          "Rosa", "Imogen", "Elena", "Sana", "Beatrix", "Niamh", "Astrid", "Zainab", "Margot",
          "Ingrid");

  /**
   * The bundled stock photo library mootmaker-webapp serves at {@code public/avatars/} - see
   * designs/person-avatar-photos.md. These filenames are a convention shared with that repo, with
   * nothing enforcing agreement between the two; PersonAvatar.tsx falls back to initials if a name
   * here ever drifts from what that build actually has bundled.
   */
  private static final List<String> FEMALE_AVATAR_PHOTOS =
      List.of(
          "avatars/female-01.jpg",
          "avatars/female-02.jpg",
          "avatars/female-03.jpg",
          "avatars/female-04.jpg",
          "avatars/female-05.jpg",
          "avatars/female-06.jpg",
          "avatars/female-07.jpg",
          "avatars/female-08.jpg",
          "avatars/female-09.jpg",
          "avatars/female-10.jpg",
          "avatars/female-11.jpg",
          "avatars/female-12.jpg");

  private static final List<String> MALE_AVATAR_PHOTOS =
      List.of(
          "avatars/male-01.jpg",
          "avatars/male-02.jpg",
          "avatars/male-03.jpg",
          "avatars/male-04.jpg",
          "avatars/male-05.jpg",
          "avatars/male-06.jpg",
          "avatars/male-07.jpg",
          "avatars/male-08.jpg",
          "avatars/male-09.jpg",
          "avatars/male-10.jpg",
          "avatars/male-11.jpg",
          "avatars/male-12.jpg");

  /**
   * A gender-matched avatar photo for {@code fullName} nine times out of ten; {@code null} (no
   * photo, falls back to initials) the rest, so a demo environment doesn't look artificially
   * complete - see designs/person-avatar-photos.md. Gender comes from {@link #FEMALE_FIRST_NAMES};
   * {@code fullName} is expected to be one {@link #personNames} produced, "First Last".
   */
  static String avatarPhotoFor(final String fullName, final Random random) {
    if (random.nextInt(10) == 0) {
      return null;
    }
    final String firstName = fullName.split(" ", 2)[0];
    final List<String> pool =
        FEMALE_FIRST_NAMES.contains(firstName) ? FEMALE_AVATAR_PHOTOS : MALE_AVATAR_PHOTOS;
    return pool.get(random.nextInt(pool.size()));
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
