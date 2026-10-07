package dev.marco035xd.terranova.api;

import java.util.UUID;
import org.bukkit.event.*;

public final class CityChangeEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID city;

  public CityChangeEvent(UUID city) {
    this.city = city;
  }

  public UUID getCityId() {
    return city;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
