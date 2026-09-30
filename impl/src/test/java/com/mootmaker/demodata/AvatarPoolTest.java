package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvatarPoolTest {

  private static final int POOL_SIZE = 2 * AvatarPool.SIZE_PER_SEX;

  // --- The bundled images -------------------------------------------------------------

  /**
   * Cheap, and catches the expensive failure: an image that did not make it onto the classpath
   * would otherwise surface part-way through seeding an environment.
   */
  @Test
  void everyBundledAvatarLoadsAndIsAnImageTheApiWillAccept() throws IOException {
    assertEquals(POOL_SIZE, AvatarPool.all().size());
    for (final AvatarPool.Avatar avatar : AvatarPool.all()) {
      final BufferedImage image = ImageIO.read(new ByteArrayInputStream(avatar.bytes()));
      assertNotNull(image, avatar.resourceName() + " does not decode");
      // The API normalises to 256x256 and rejects anything under 64 or over 4096 a side.
      assertTrue(
          image.getWidth() >= 256 && image.getWidth() <= 4096, avatar.resourceName() + " width");
      assertTrue(
          image.getHeight() >= 256 && image.getHeight() <= 4096, avatar.resourceName() + " height");
      assertTrue(
          avatar.bytes().length <= 2 * 1024 * 1024, avatar.resourceName() + " exceeds 2 MiB");
      assertEquals(
          (byte) 0xFF, avatar.bytes()[0], avatar.resourceName() + " is not a JPEG, as declared");
    }
  }

  @Test
  void eachSexHasItsOwnHalfOfThePool() {
    for (final SampleData.Sex sex : SampleData.Sex.values()) {
      final List<AvatarPool.Avatar> half =
          AvatarPool.all().stream().filter(avatar -> avatar.sex() == sex).toList();
      assertEquals(AvatarPool.SIZE_PER_SEX, half.size(), sex.name());
      final String prefix =
          sex == SampleData.Sex.FEMALE ? "avatars-photo/woman-" : "avatars-photo/man-";
      for (final AvatarPool.Avatar avatar : half) {
        assertTrue(avatar.resourceName().startsWith(prefix), avatar.resourceName());
      }
    }
  }

  /**
   * Images are told apart by hash. Two byte-identical files would be one avatar as far as the
   * read-back can tell, and the second would be handed out while the first was in use.
   */
  @Test
  void everyBundledAvatarIsDistinct() {
    final Set<String> hashes =
        AvatarPool.all().stream().map(AvatarPool.Avatar::sha256).collect(Collectors.toSet());

    assertEquals(POOL_SIZE, hashes.size());
  }

  @Test
  void thePoolOnDiskIsExactlyThePoolTheCodeExpects() throws IOException {
    // A stray or misnumbered file would be bundled and never used - or worse, leave a gap.
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/avatars-photo"))) {
      final Set<String> onDisk =
          files.map(path -> "avatars-photo/" + path.getFileName()).collect(Collectors.toSet());
      final Set<String> expected = new HashSet<>();
      for (final SampleData.Sex sex : SampleData.Sex.values()) {
        for (int number = 1; number <= AvatarPool.SIZE_PER_SEX; number++) {
          expected.add(AvatarPool.resourceName(sex, number));
        }
      }
      assertEquals(expected, onDisk);
    }
  }

  // --- Assignment ---------------------------------------------------------------------

  @Test
  void assignsOnePositionPerPersonAndNeverTheSameImageTwice() {
    final List<String> names = SampleData.personNames(150, new Random(7));

    final List<AvatarPool.Avatar> assigned = AvatarPool.assign(names, Set.of(), new Random(7));

    assertEquals(150, assigned.size());
    final List<AvatarPool.Avatar> withOne = assigned.stream().filter(Objects::nonNull).toList();
    assertEquals(withOne.size(), Set.copyOf(withOne).size());
    assertTrue(withOne.size() < 150, "some people must be left without, to show the fallback");
  }

  /** The case photorealistic-demo-avatars.md re-introduces: a photograph matches its name. */
  @Test
  void everyAssignedPhotographMatchesTheSexTheNameIsTaggedWith() {
    final List<String> names = SampleData.personNames(150, new Random(11));

    final List<AvatarPool.Avatar> assigned = AvatarPool.assign(names, Set.of(), new Random(11));

    int checked = 0;
    for (int i = 0; i < names.size(); i++) {
      if (assigned.get(i) != null) {
        assertEquals(
            SampleData.sexOf(names.get(i)),
            assigned.get(i).sex(),
            names.get(i) + " got " + assigned.get(i).resourceName());
        checked++;
      }
    }
    assertTrue(checked > 100, "the check must actually have covered people, got " + checked);
  }

  @Test
  void skipsImagesAlreadyInUse() {
    final Set<String> inUse =
        AvatarPool.all().stream()
            .filter(avatar -> !avatar.resourceName().endsWith("-100.jpg"))
            .map(AvatarPool.Avatar::sha256)
            .collect(Collectors.toSet());

    final List<AvatarPool.Avatar> assigned =
        AvatarPool.assign(List.of("Amelia Whitfield"), inUse, new Random(3));

    // Nine in ten get one; with a seed where this one does, it can only be the one woman left.
    if (assigned.getFirst() != null) {
      assertEquals("avatars-photo/woman-100.jpg", assigned.getFirst().resourceName());
    }
  }

  @Test
  @DisplayName("one sex running out is an error, not a reason to use the other sex's photographs")
  void throwsRatherThanCrossingOverWhenOneSexIsExhausted() {
    final Set<String> allWomenInUse =
        AvatarPool.all().stream()
            .filter(avatar -> avatar.sex() == SampleData.Sex.FEMALE)
            .map(AvatarPool.Avatar::sha256)
            .collect(Collectors.toSet());
    // Twenty women, every woman's photograph taken - but every man's still free.
    final List<String> women = Collections.nCopies(20, "Amelia Whitfield");

    assertThrows(
        IllegalStateException.class, () -> AvatarPool.assign(women, allWomenInUse, new Random(7)));
  }

  @Test
  void hashesThatAreNotOursDoNotShrinkThePool() {
    // A person with an avatar uploaded some other way holds a hash this pool has never seen.
    final List<AvatarPool.Avatar> assigned =
        AvatarPool.assign(
            SampleData.personNames(10, new Random(7)), Set.of("f".repeat(64)), new Random(7));

    assertEquals(10, assigned.size());
  }

  // --- Sex from a name ----------------------------------------------------------------

  @Test
  void aNameIsReadByItsTaggedFirstName() {
    assertEquals(SampleData.Sex.FEMALE, SampleData.sexOf("Amelia Whitfield"));
    assertEquals(SampleData.Sex.MALE, SampleData.sexOf("Noah Whitfield"));
    // Untagged means male, as it always has.
    assertEquals(SampleData.Sex.MALE, SampleData.sexOf("Someone Unlisted"));
  }

  @Test
  void exactlyHalfOfTheFirstNamesAreTaggedFemale() {
    assertTrue(SampleData.FIRST_NAMES.containsAll(SampleData.FEMALE_FIRST_NAMES));
    assertEquals(SampleData.FIRST_NAMES.size() / 2, SampleData.FEMALE_FIRST_NAMES.size());
  }

  // --- Reading a hash back off a URL --------------------------------------------------

  @Test
  void readsTheHashSegmentWhateverTheHostOrPerson() {
    final String hash = "3f9a".repeat(16);

    assertEquals(
        Optional.of(hash),
        AvatarPool.hashOf("https://avatars.mootmaker.com/v1/abc12345/" + hash + ".jpg"));
    assertEquals(
        Optional.of(hash),
        AvatarPool.hashOf("https://avatars.some-env.mootmaker.com/v1/zzz99999/" + hash + ".jpg"));
  }

  @Test
  void aPersonWithNoAvatarOrAnUnrecognisedUrlHasNoHash() {
    assertEquals(Optional.empty(), AvatarPool.hashOf(null));
    assertEquals(Optional.empty(), AvatarPool.hashOf("/avatars/female-01.jpg"));
    assertEquals(Optional.empty(), AvatarPool.hashOf("https://example.test/not-a-hash.jpg"));
  }
}
