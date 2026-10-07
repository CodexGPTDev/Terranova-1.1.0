package dev.marco035xd.terranova.model;

import java.util.*;

/** Exact cuboids are candidates in a chunk index; very large regions use a bounded fallback. */
public final class SpatialIndex {
  private record Key(UUID world, int x, int z) {}

  private final Map<Key, Set<UUID>> chunks = new HashMap<>();
  private final Map<UUID, Cuboid> bounds = new LinkedHashMap<>();
  private final Set<UUID> large = new HashSet<>();
  private final int maxChunks;

  public SpatialIndex(int maxChunks) {
    this.maxChunks = Math.max(1, maxChunks);
  }

  public synchronized void put(UUID id, Cuboid b) {
    remove(id);
    bounds.put(id, b);
    if (b.chunkCount() > maxChunks) {
      large.add(id);
      return;
    }
    for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++)
      for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++)
        chunks.computeIfAbsent(new Key(b.world(), x, z), k -> new HashSet<>()).add(id);
  }

  public synchronized void remove(UUID id) {
    Cuboid b = bounds.remove(id);
    if (b == null) return;
    if (large.remove(id)) return;
    for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++)
      for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
        Key k = new Key(b.world(), x, z);
        Set<UUID> s = chunks.get(k);
        if (s != null) {
          s.remove(id);
          if (s.isEmpty()) chunks.remove(k);
        }
      }
  }

  public synchronized UUID at(UUID w, int x, int y, int z) {
    for (UUID id : candidates(w, x >> 4, z >> 4)) {
      if (bounds.get(id).contains(w, x, y, z)) return id;
    }
    return null;
  }

  private Set<UUID> candidates(UUID w, int x, int z) {
    Set<UUID> out = new LinkedHashSet<>(chunks.getOrDefault(new Key(w, x, z), Set.of()));
    out.addAll(large);
    return out;
  }

  public synchronized boolean overlaps(Cuboid b, UUID except) {
    if (b.chunkCount() > maxChunks)
      return bounds.entrySet().stream()
          .anyMatch(e -> !e.getKey().equals(except) && e.getValue().overlaps(b));
    Set<UUID> seen = new HashSet<>();
    for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++)
      for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) seen.addAll(candidates(b.world(), x, z));
    return seen.stream().anyMatch(id -> !id.equals(except) && bounds.get(id).overlaps(b));
  }

  public synchronized int indexedChunks() {
    return chunks.size();
  }

  public synchronized Set<UUID> nearby(UUID world, int x, int z, int distance) {
    int minX = (x - distance) >> 4,
        maxX = (x + distance) >> 4,
        minZ = (z - distance) >> 4,
        maxZ = (z + distance) >> 4;
    Set<UUID> out = new HashSet<>(large);
    for (int cx = minX; cx <= maxX; cx++)
      for (int cz = minZ; cz <= maxZ; cz++)
        out.addAll(chunks.getOrDefault(new Key(world, cx, cz), Set.of()));
    return out;
  }
}
