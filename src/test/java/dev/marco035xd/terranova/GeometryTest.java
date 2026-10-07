package dev.marco035xd.terranova;

import static org.junit.jupiter.api.Assertions.*;

import dev.marco035xd.terranova.model.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeometryTest {
  private final UUID w = UUID.randomUUID();

  @Test
  void selectionIsInclusiveAndOrderIndependent() {
    Cuboid b = Cuboid.between(w, 2, 5, 8, -1, 0, 6);
    assertEquals(72, b.volume());
    assertTrue(b.contains(w, -1, 0, 6));
    assertTrue(b.contains(w, 2, 5, 8));
    assertFalse(b.contains(w, 3, 5, 8));
  }

  @Test
  void fullContainmentRejectsCrossWorldAndEscapingCorner() {
    Cuboid city = new Cuboid(w, -15, -64, -15, 15, 319, 15);
    assertTrue(city.contains(new Cuboid(w, -15, -64, -15, 15, 319, 15)));
    assertFalse(city.contains(new Cuboid(w, -16, 0, 0, 0, 10, 10)));
    assertFalse(city.contains(new Cuboid(UUID.randomUUID(), 0, 0, 0, 1, 1, 1)));
  }

  @Test
  void sharedBoundaryIsOverlapButAdjacentBlocksAreNot() {
    Cuboid a = new Cuboid(w, 0, 0, 0, 15, 10, 15);
    assertTrue(a.overlaps(new Cuboid(w, 15, 0, 0, 30, 10, 15)));
    assertFalse(a.overlaps(new Cuboid(w, 16, 0, 0, 31, 10, 15)));
  }

  @Test
  void volumeOverflowCannotTurnHugeSelectionIntoCheapPurchase() {
    assertEquals(
        Long.MAX_VALUE,
        new Cuboid(
                w,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE)
            .volume());
  }

  @Test
  void indexHandlesNegativeChunksAndChangesAtomically() {
    SpatialIndex index = new SpatialIndex(100);
    UUID city = UUID.randomUUID();
    index.put(city, new Cuboid(w, -17, -64, -17, -1, 319, -1));
    assertEquals(city, index.at(w, -17, 0, -17));
    assertNull(index.at(w, 0, 0, 0));
    index.put(city, new Cuboid(w, 32, 0, 32, 47, 100, 47));
    assertNull(index.at(w, -17, 0, -17));
    assertEquals(city, index.at(w, 40, 10, 40));
    index.remove(city);
    assertEquals(0, index.indexedChunks());
  }

  @Test
  void enormousRegionUsesBoundedFallbackAndStillBlocksOverlap() {
    SpatialIndex index = new SpatialIndex(4);
    UUID city = UUID.randomUUID();
    index.put(city, new Cuboid(w, -1000000, -64, -1000000, 1000000, 319, 1000000));
    assertEquals(0, index.indexedChunks());
    assertEquals(city, index.at(w, 999999, 100, 999999));
    assertTrue(index.overlaps(new Cuboid(w, 5, 0, 5, 8, 4, 8), null));
    assertFalse(index.overlaps(new Cuboid(w, 5, 0, 5, 8, 4, 8), city));
  }
}
