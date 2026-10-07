package dev.marco035xd.terranova.crystal;

import java.util.*;

public final class CrystalEffectRegistry {
  private final Map<String, CrystalEffectHandler> handlers = new LinkedHashMap<>();

  public void register(CrystalEffectHandler handler) {
    if (handlers.putIfAbsent(handler.path(), handler) != null)
      throw new IllegalArgumentException("Módulo duplicado: " + handler.path());
  }

  public Collection<CrystalEffectHandler> handlers() {
    return Collections.unmodifiableCollection(handlers.values());
  }
}
