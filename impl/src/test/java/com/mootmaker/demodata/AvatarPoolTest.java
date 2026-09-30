package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarPoolTest {

  // --- The bundled images -------------------------------------------------------------

  /**
   * Cheap, and catches the expensive failure: an image that did not make it onto the classpath
   * would otherwise surface part-way through seeding an environment.
   */
  @Test
  void everyBundledAvatarLoadsAndIsAnImageTheApiWillAccept() throws IOException {
    assertEquals(AvatarPool.SIZE, AvatarPool.all().size());
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
          (byte) 0x89, avatar.bytes()[0], avatar.resourceName() + " is not a PNG, as declared");
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

    assertEquals(AvatarPool.SIZE, hashes.size());
  }

  @Test
  void thePoolOnDiskIsExactlyThePoolTheCodeExpects() throws IOException {
    // A stray or misnumbered file would be bundled and never used - or worse, leave a gap.
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/avatars"))) {
      final Set<String> onDisk =
          files.map(path -> "avatars/" + path.getFileName()).collect(Collectors.toSet());
      final Set<String> expected =
          IntStream.rangeClosed(1, AvatarPool.SIZE)
              .mapToObj(AvatarPool::resourceName)
              .collect(Collectors.toSet());
      assertEquals(expected, onDisk);
    }
  }

  // --- Assignment ---------------------------------------------------------------------

  @Test
  void assignsOnePositionPerPersonAndNeverTheSameImageTwice() {
    final List<AvatarPool.Avatar> assigned = AvatarPool.assign(150, Set.of(), new Random(7));

    assertEquals(150, assigned.size());
    final List<AvatarPool.Avatar> withOne = assigned.stream().filter(Objects::nonNull).toList();
    assertEquals(withOne.size(), Set.copyOf(withOne).size());
    assertTrue(withOne.size() < 150, "some people must be left without, to show the fallback");
  }

  @Test
  void skipsImagesAlreadyInUse() {
    final Set<String> inUse =
        AvatarPool.all().subList(0, 190).stream()
            .map(AvatarPool.Avatar::sha256)
            .collect(Collectors.toSet());

    final List<AvatarPool.Avatar> assigned = AvatarPool.assign(8, inUse, new Random(7));

    for (final AvatarPool.Avatar avatar : assigned) {
      assertFalse(avatar != null && inUse.contains(avatar.sha256()));
    }
  }

  @Test
  void throwsRatherThanRepeatingWhenThePoolIsExhausted() {
    final Set<String> allInUse =
        AvatarPool.all().stream().map(AvatarPool.Avatar::sha256).collect(Collectors.toSet());

    assertThrows(IllegalStateException.class, () -> AvatarPool.assign(20, allInUse, new Random(7)));
  }

  @Test
  void hashesThatAreNotOursDoNotShrinkThePool() {
    // A person with an avatar uploaded some other way holds a hash this pool has never seen.
    final List<AvatarPool.Avatar> assigned =
        AvatarPool.assign(10, Set.of("f".repeat(64)), new Random(7));

    assertEquals(10, assigned.size());
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
