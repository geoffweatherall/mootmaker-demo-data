package com.mootmaker.demodata;

import module java.base;

/**
 * The avatar images bundled with this component, and the rule for handing them out: nobody in an
 * environment ever gets an image somebody else already has.
 *
 * <p>The images are {@value #SIZE} pre-generated PNGs on the classpath under {@code avatars/} -
 * DiceBear's "Notionists Neutral" style, whose design is CC0 1.0. They are generated once, at
 * authoring time, by {@code tools/generate-avatar-pool.sh} and committed; nothing here runs Node or
 * calls DiceBear. They are line drawings, deliberately not photographs of anyone.
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
   * How many images are bundled. Must stay comfortably above any plausible {@code TARGET_PEOPLE},
   * since running out is an error rather than a reason to repeat one. Keep in step with {@code
   * POOL_SIZE} in {@code tools/generate-avatar-pool.sh}.
   */
  static final int SIZE = 200;

  /** What every bundled image is, and so what is declared when requesting an upload. */
  static final String CONTENT_TYPE = "image/png";

  /** One person in ten gets no avatar, so a demo environment shows the initials fallback too. */
  private static final int ONE_IN_N_WITHOUT = 10;

  private static final Pattern HASH_SEGMENT = Pattern.compile("/([0-9a-f]{64})\\.[a-z]+$");

  /** One bundled image: its bytes, and the hash the API will key it by. */
  record Avatar(String resourceName, byte[] bytes, String sha256) {}

  /** Loaded on first use and kept: 1.3 MB, read once per Lambda execution environment. */
  private static final class Holder {
    static final List<Avatar> ALL = load();
  }

  private AvatarPool() {}

  static List<Avatar> all() {
    return Holder.ALL;
  }

  /**
   * Chooses an avatar for each of {@code count} new people: an image nobody has yet, or null for
   * the one in ten who get none. The result is positional - element {@code i} is for person {@code
   * i}.
   *
   * <p>Everything is decided here, up front and sequentially, because {@link Random} is not safe
   * for concurrent use and because deciding before any person is created means a pool that cannot
   * cover the batch fails before the environment has been changed at all.
   *
   * @param hashesInUse the hash segment of every existing person's {@code avatarUrl} - see {@link
   *     #hashOf}
   * @throws IllegalStateException if more images are needed than remain unused. Deliberately not a
   *     fallback to repeating one, mirroring {@link SampleData#personNames} refusing to repeat a
   *     name
   */
  static List<Avatar> assign(final int count, final Set<String> hashesInUse, final Random random) {
    final List<Avatar> unused =
        new ArrayList<>(
            all().stream().filter(avatar -> !hashesInUse.contains(avatar.sha256())).toList());
    Collections.shuffle(unused, random);

    final List<Avatar> assigned = new ArrayList<>(count);
    int next = 0;
    for (int i = 0; i < count; i++) {
      if (random.nextInt(ONE_IN_N_WITHOUT) == 0) {
        assigned.add(null);
        continue;
      }
      if (next == unused.size()) {
        throw new IllegalStateException(
            "Out of avatars: "
                + count
                + " new people need more than the "
                + unused.size()
                + " images not already in use ("
                + SIZE
                + " bundled). An avatar is never given to two people - add to the pool with"
                + " tools/generate-avatar-pool.sh rather than lowering this guard.");
      }
      assigned.add(unused.get(next++));
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

  static String resourceName(final int number) {
    return String.format("avatars/avatar-%03d.png", number);
  }

  private static List<Avatar> load() {
    final List<Avatar> avatars = new ArrayList<>(SIZE);
    for (int number = 1; number <= SIZE; number++) {
      final String name = resourceName(number);
      try (InputStream stream = AvatarPool.class.getClassLoader().getResourceAsStream(name)) {
        if (stream == null) {
          // A resource that did not make it into the shaded jar. Fail at load, naming it, rather
          // than part-way through seeding an environment.
          throw new IllegalStateException("Bundled avatar is missing from the classpath: " + name);
        }
        final byte[] bytes = stream.readAllBytes();
        avatars.add(new Avatar(name, bytes, sha256Hex(bytes)));
      } catch (final IOException e) {
        throw new UncheckedIOException("Failed to read bundled avatar " + name, e);
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
