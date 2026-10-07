package dev.marco035xd.terranova.model;

import java.util.UUID;

/** Inclusive integer bounds, independent of loaded chunks and terrain. */
public record Cuboid(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public Cuboid {
    if (world == null || minX > maxX || minY > maxY || minZ > maxZ)
      throw new IllegalArgumentException("Invalid cuboid");
  }

  public static Cuboid between(UUID world, int x1, int y1, int z1, int x2, int y2, int z2) {
    return new Cuboid(
        world,
        Math.min(x1, x2),
        Math.min(y1, y2),
        Math.min(z1, z2),
        Math.max(x1, x2),
        Math.max(y1, y2),
        Math.max(z1, z2));
  }

  public boolean contains(UUID w, int x, int y, int z) {
    return world.equals(w)
        && x >= minX
        && x <= maxX
        && y >= minY
        && y <= maxY
        && z >= minZ
        && z <= maxZ;
  }

  public boolean contains(Cuboid b) {
    return contains(b.world, b.minX, b.minY, b.minZ) && contains(b.world, b.maxX, b.maxY, b.maxZ);
  }

  public boolean overlaps(Cuboid b) {
    return world.equals(b.world)
        && minX <= b.maxX
        && maxX >= b.minX
        && minY <= b.maxY
        && maxY >= b.minY
        && minZ <= b.maxZ
        && maxZ >= b.minZ;
  }

  public long volume() {
    try {
      return Math.multiplyExact(
          Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
          (long) maxZ - minZ + 1);
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  public long chunkCount() {
    return ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
  }
}
