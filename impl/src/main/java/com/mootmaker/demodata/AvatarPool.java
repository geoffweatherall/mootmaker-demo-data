package com.mootmaker.demodata;

import module java.base;

/**
 * The avatar photographs bundled with this component, and the rules for handing them out: a person
 * gets a photograph whose apparent sex matches their name, and nobody in an environment ever gets
 * one somebody else already has.
 *
 * <p>The images are {@value #SIZE_PER_SEX} men and {@value #SIZE_PER_SEX} women, 512x512 JPEGs on
 * the classpath under {@code avatars-photo/}. <b>None of them is a real person</b>: they were
 * generated with Stable Diffusion by the tool in {@code photorealistic-avatar-generator/}, whose
 * {@code manifest.json} records each one's prompt and seed. They are committed; nothing here runs a
 * model. See mootmaker/designs/archive/photorealistic-demo-avatars.md.
 *
 * <p><b>Which images are in use is read back from the API, not remembered.</b> mootmaker-api keys
 * every avatar by the SHA-256 of the bytes that were uploaded, and that hash is the last path
 * segment of {@code Person.avatarUrl}. So this class hashes its own files, and an image is in use
 * exactly when its hash appears in some person's URL. That holds across runs, across a redeploy of
 * this Lambda, and for a person created by hand - none of which a list kept here could see.
 *
 * <p>It depends only on the <i>source</i> bytes, which this component holds. It does not depend on
 * the API's image processing being reproducible, nor on which host serves avatars: only the hash
 * segment is compared, never the whole URL, which differs per environment and per person.
 */
final class AvatarPool {

  /**
   * How many photographs are bundled of each sex. The two halves are exhausted independently, so
   * each must stay comfortably above the number of that sex any plausible {@code TARGET_PEOPLE}
   * produces - about half of it, since half of {@link SampleData#FIRST_NAMES} are tagged female.
   * Running out is an error, never a reason to repeat an image or to cross over to the other half.
   */
  static final int SIZE_PER_SEX = 100;

  /** What every bundled image is, and so what is declared when requesting an upload. */
  static final String CONTENT_TYPE = "image/jpeg";

  /** One person in ten gets no avatar, so a demo environment shows the initials fallback too. */
  private static final int ONE_IN_N_WITHOUT = 10;

  private static final Pattern HASH_SEGMENT = Pattern.compile("/([0-9a-f]{64})\\.[a-z]+$");

  /** One bundled image: its bytes, and the hash the API will key it by. */
  record Avatar(String resourceName, SampleData.Sex sex, byte[] bytes, String sha256) {}

  /** Loaded on first use and kept: 10 MB, read once per Lambda execution environment. */
  private static final class Holder {
    static final List<Avatar> ALL = load();
  }

  private AvatarPool() {}

  static List<Avatar> all() {
    return Holder.ALL;
  }

  /**
   * Chooses an avatar for each new person: a photograph nobody has yet, of the sex their name is
   * tagged with, or null for the one in ten who get none. The result is positional - element {@code
   * i} is for {@code names.get(i)}.
   *
   * <p>Everything is decided here, up front and sequentially, because {@link Random} is not safe
   * for concurrent use and because deciding before any person is created means a pool that cannot
   * cover the batch fails before the environment has been changed at all.
   *
   * @param hashesInUse the hash segment of every existing person's {@code avatarUrl} - see {@link
   *     #hashOf}
   * @throws IllegalStateException if more photographs of one sex are needed than remain unused.
   *     Deliberately not a fallback to repeating one, nor to the other sex's half, mirroring {@link
   *     SampleData#personNames} refusing to repeat a name
   */
  static List<Avatar> assign(
      final List<String> names, final Set<String> hashesInUse, final Random random) {
    final Map<SampleData.Sex, Deque<Avatar>> unusedBySex = new EnumMap<>(SampleData.Sex.class);
    for (final SampleData.Sex sex : SampleData.Sex.values()) {
      final List<Avatar> unused =
          new ArrayList<>(
              all().stream()
                  .filter(avatar -> avatar.sex() == sex)
                  .filter(avatar -> !hashesInUse.contains(avatar.sha256()))
                  .toList());
      Collections.shuffle(unused, random);
      unusedBySex.put(sex, new ArrayDeque<>(unused));
    }

    final List<Avatar> assigned = new ArrayList<>(names.size());
    for (final String name : names) {
      if (random.nextInt(ONE_IN_N_WITHOUT) == 0) {
        assigned.add(null);
        continue;
      }
      final SampleData.Sex sex = SampleData.sexOf(name);
      final Avatar next = unusedBySex.get(sex).poll();
      if (next == null) {
        throw new IllegalStateException(
            "Out of "
                + sex.name().toLowerCase(Locale.ROOT)
                + " avatars: this batch of "
                + names.size()
                + " people needs more than remain unused ("
                + SIZE_PER_SEX
                + " of each sex bundled). An avatar is never given to two people, nor to a name"
                + " of the other sex - add to the pool with photorealistic-avatar-generator/"
                + " rather than lowering this guard.");
      }
      assigned.add(next);
    }
    return assigned;
  }

  /**
   * The hash segment of an {@code avatarUrl}, e.g. {@code 3f9a...} from {@code
   * https://avatars.mootmaker.com/v1/abc12345/3f9a....jpg}. Empty for a person with no avatar, and
   * for a URL that is not in that shape - which is then simply not one of ours.
   */
  static Optional<String> hashOf(final String avatarUrl) {
    if (avatarUrl == null) {
      return Optional.empty();
    }
    final Matcher matcher = HASH_SEGMENT.matcher(avatarUrl);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  static String resourceName(final SampleData.Sex sex, final int number) {
    return "avatars-photo/"
        + (sex == SampleData.Sex.FEMALE ? "woman" : "man")
        + "-"
        + number
        + ".jpg";
  }

  private static List<Avatar> load() {
    final List<Avatar> avatars = new ArrayList<>(2 * SIZE_PER_SEX);
    for (final SampleData.Sex sex : SampleData.Sex.values()) {
      for (int number = 1; number <= SIZE_PER_SEX; number++) {
        final String name = resourceName(sex, number);
        try (InputStream stream = AvatarPool.class.getClassLoader().getResourceAsStream(name)) {
          if (stream == null) {
            // A resource that did not make it into the shaded jar. Fail at load, naming it, rather
            // than part-way through seeding an environment.
            throw new IllegalStateException(
                "Bundled avatar is missing from the classpath: " + name);
          }
          final byte[] bytes = stream.readAllBytes();
          avatars.add(new Avatar(name, sex, bytes, sha256Hex(bytes)));
        } catch (final IOException e) {
          throw new UncheckedIOException("Failed to read bundled avatar " + name, e);
        }
      }
    }
    return List.copyOf(avatars);
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
