package dev.marco035xd.terranova.model;

import java.util.Set;
import java.util.UUID;

public enum Audience {
  ALL,
  MEMBERS,
  NON_MEMBERS,
  OWNER,
  NON_OWNER;

  public boolean matches(UUID player, UUID owner, Set<UUID> members) {
    boolean o = player != null && player.equals(owner), m = o || members.contains(player);
    return switch (this) {
      case ALL -> true;
      case MEMBERS -> m;
      case NON_MEMBERS -> !m;
      case OWNER -> o;
      case NON_OWNER -> !o;
    };
  }

  public static Audience parse(String s) {
    if (s == null) return ALL;
    return valueOf(
        s.toUpperCase(java.util.Locale.ROOT)
            .replace("NON_OWNERS", "NON_OWNER")
            .replace("OWNERS", "OWNER"));
  }
}
