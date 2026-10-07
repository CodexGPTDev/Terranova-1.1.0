package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class SubzoneService {
  public enum Validation {
    OK,
    OUTSIDE_CITY,
    TOO_LARGE,
    OVERLAPPING_SUBZONE,
    INSUFFICIENT_LEVEL,
    MAX_SUBZONES_REACHED,
    INSUFFICIENT_FUNDS,
    INCOMPLETE_SELECTION
  }

  public static final class Selection {
    public Location first, second;

    public Cuboid box() {
      if (first == null || second == null || !first.getWorld().equals(second.getWorld()))
        return null;
      return Cuboid.between(
          first.getWorld().getUID(),
          first.getBlockX(),
          first.getBlockY(),
          first.getBlockZ(),
          second.getBlockX(),
          second.getBlockY(),
          second.getBlockZ());
    }
  }

  private final TerranovaPlugin p;
  private final Map<UUID, Selection> selections = new HashMap<>();

  public SubzoneService(TerranovaPlugin p) {
    this.p = p;
  }

  public ItemStack wand() {
    return p.items()
        .signed(
            p.items()
                .simple(
                    Material.BLAZE_ROD,
                    "&#66E6B5Selector de subzonas",
                    "",
                    " &8│ &7Clic izquierdo: &fPosición 1",
                    " &8│ &7Clic derecho: &fPosición 2",
                    "",
                    "&e/city subzone create <nombre>"),
            "wand",
            "selection",
            new UUID(0, 1));
  }

  public void select(Player player, Location at, boolean first) {
    Selection s = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
    if (first) s.first = at.clone();
    else s.second = at.clone();
    show(player, s);
  }

  public Selection selection(UUID u) {
    return selections.get(u);
  }

  public void cancel(UUID u) {
    selections.remove(u);
  }

  public double cost(Cuboid b) {
    return Text.money(b.volume() * p.catalog().settings.getDouble("subzones.price-per-block", 2));
  }

  public Validation validate(City city, Cuboid box) {
    if (box == null) return Validation.INCOMPLETE_SELECTION;
    if (city == null || !city.bounds.contains(box)) return Validation.OUTSIDE_CITY;
    var l = p.cities().level(city);
    if (city.level < p.catalog().settings.getInt("subzones.minimum-city-level", 1))
      return Validation.INSUFFICIENT_LEVEL;
    if (city.subzones.size() >= l.maxSubzones()) return Validation.MAX_SUBZONES_REACHED;
    if (box.volume() > l.maxSubzoneBlocks()) return Validation.TOO_LARGE;
    if (city.subzones.stream().anyMatch(s -> s.bounds.overlaps(box)))
      return Validation.OVERLAPPING_SUBZONE;
    return Validation.OK;
  }

  public void create(Player player, City city, String name) {
    if (!p.cities().manager(player, city))
      throw new IllegalArgumentException("Solo el propietario crea subzonas");
    if (!name.matches("[\\p{L}0-9 _-]{1,32}")
        || city.subzones.stream().anyMatch(s -> s.name.equalsIgnoreCase(name)))
      throw new IllegalArgumentException("Nombre de subzona inválido o repetido");
    Selection selection = selections.get(player.getUniqueId());
    Cuboid b = selection == null ? null : selection.box();
    Validation valid = validate(city, b);
    if (valid != Validation.OK) throw new IllegalArgumentException(valid.name());
    double cost = cost(b);
    String currency = p.catalog().settings.getString("subzones.currency", "vault");
    p.cities()
        .paid(
            player,
            city,
            () -> p.economy().charge(player, currency, cost),
            c -> {
              if (validate(c, b) != Validation.OK)
                throw new IllegalArgumentException(validate(c, b).name());
              City.Subzone s = new City.Subzone();
              s.name = name;
              s.bounds = b;
              s.owner = player.getUniqueId();
              s.flags.putAll(c.flags);
              c.subzones.add(s);
            },
            () -> {
              cancel(player.getUniqueId());
              p.message(player, "subzone-created");
            });
  }

  public City.Subzone find(City city, String name, Location at) {
    if (city == null) return null;
    if (name != null)
      return city.subzones.stream()
          .filter(s -> s.name.equalsIgnoreCase(name) || s.id.toString().equals(name))
          .findFirst()
          .orElse(null);
    return city.subzone(at.getBlockX(), at.getBlockY(), at.getBlockZ());
  }

  public boolean owner(Player player, City city, City.Subzone sub) {
    return sub != null
        && (sub.owner.equals(player.getUniqueId())
            || p.cities().manager(player, city)
                && p.catalog().settings.getBoolean("subzones.city-owner-access", true));
  }

  public void sell(Player player, City city, City.Subzone sub, double price, String currency) {
    if (!owner(player, city, sub)) throw new IllegalArgumentException("Sin permiso");
    Text.money(price);
    if (price <= 0 || !p.economy().available(currency))
      throw new IllegalArgumentException("Precio o moneda inválidos");
    p.cities()
        .update(
            player,
            city,
            c -> {
              var s = find(c, sub.id.toString(), player.getLocation());
              s.forSale = true;
              s.salePrice = price;
              s.saleCurrency = currency;
            },
            () -> p.message(player, "subzone-listed"));
  }

  public void buy(Player player, City city, City.Subzone sub) {
    if (sub == null || !sub.forSale || sub.owner.equals(player.getUniqueId()))
      throw new IllegalArgumentException("Subzona no disponible");
    UUID seller = sub.owner;
    double price = sub.salePrice;
    String currency = sub.saleCurrency;
    p.cities()
        .paidTransfer(
            player,
            city,
            currency,
            price,
            seller,
            c -> {
              var s = find(c, sub.id.toString(), player.getLocation());
              if (s == null
                  || !s.forSale
                  || !s.owner.equals(seller)
                  || s.salePrice != price
                  || !s.saleCurrency.equals(currency))
                throw new IllegalArgumentException("La oferta cambió");
              s.owner = player.getUniqueId();
              s.members.clear();
              s.individual.clear();
              s.forSale = false;
            },
            () -> p.message(player, "subzone-purchased"));
  }

  private void show(Player player, Selection s) {
    Cuboid b = s.box();
    if (b != null) {
      Text.action(
          player,
          "&fTamaño: &e"
              + b.volume()
              + " bloques &8| &fCosto: &a"
              + String.format(Locale.ROOT, "%.2f", cost(b))
              + " monedas");
      p.visual().draw(player, b, player.getLocation().getY(), true);
    }
  }

  public void tick() {
    for (var e : selections.entrySet()) {
      Player player = Bukkit.getPlayer(e.getKey());
      if (player != null) show(player, e.getValue());
    }
  }
}
