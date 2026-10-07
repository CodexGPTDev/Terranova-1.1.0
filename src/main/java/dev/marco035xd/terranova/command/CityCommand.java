package dev.marco035xd.terranova.command;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.gui.GuiService.Screen;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class CityCommand implements CommandExecutor, TabCompleter {
  private final TerranovaPlugin p;

  public CityCommand(TerranovaPlugin p) {
    this.p = p;
  }

  private void permit(CommandSender s, String node) {
    if (!s.hasPermission("terranova.admin") && !s.hasPermission("terranova.command." + node))
      throw new IllegalArgumentException("Sin permiso: terranova.command." + node);
  }

  private Player player(CommandSender s) {
    if (!(s instanceof Player player))
      throw new IllegalArgumentException("Este comando requiere un jugador");
    return player;
  }

  private City city(Player player) {
    City c = p.cities().at(player.getLocation());
    if (c == null) c = p.cities().primary(player.getUniqueId());
    if (c == null) throw new IllegalArgumentException("No tienes una ciudad");
    return c;
  }

  private void manager(Player player, City city) {
    if (!p.cities().manager(player, city))
      throw new IllegalArgumentException("Solo el propietario puede hacerlo");
  }

  private String arg(String[] a, int i, String format) {
    if (a.length <= i) throw new IllegalArgumentException("Uso: /city " + format);
    return a[i];
  }

  private UUID resolve(String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) return online.getUniqueId();
    try {
      return UUID.fromString(name);
    } catch (IllegalArgumentException ignored) {
    }
    OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
    if (!offline.hasPlayedBefore())
      throw new IllegalArgumentException(
          "Jugador desconocido; usa su UUID o espera su primera conexión");
    return offline.getUniqueId();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
    try {
      String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
      permit(sender, sub);
      switch (sub) {
        case "help" -> {
          Text.send(sender, "&#66E6B5Terranova &8│ &fCiudades, cristales y subzonas");
          for (String line : p.catalog().messages.getStringList("help")) Text.send(sender, line);
        }
        case "give" -> {
          permit(sender, "give");
          Player target = Bukkit.getPlayerExact(arg(a, 1, "give <jugador> <tipo> [cantidad]"));
          if (target == null) throw new IllegalArgumentException("Jugador no conectado");
          grant(
              sender,
              target,
              "core",
              arg(a, 2, "give <jugador> <tipo> [cantidad]"),
              a.length > 3 ? Integer.parseInt(a[3]) : 1);
        }
        case "crystal" -> {
          String act = arg(a, 1, "crystal give <jugador> <cristal> [cantidad]");
          permit(sender, "crystal." + act);
          if (!act.equals("give")) throw new IllegalArgumentException("Usa crystal give");
          Player target = Bukkit.getPlayerExact(arg(a, 2, "crystal give <jugador> <cristal>"));
          if (target == null) throw new IllegalArgumentException("Jugador no conectado");
          grant(
              sender,
              target,
              "crystal",
              arg(a, 3, "crystal give <jugador> <cristal>"),
              a.length > 4 ? Integer.parseInt(a[4]) : 1);
        }
        case "claim" -> p.delivery().claim(player(sender));
        case "event" -> {
          String act = arg(a, 1, "event start|stop <id> [duración]");
          permit(sender, "event." + act);
          String id = arg(a, 2, "event start|stop <id> [duración]");
          if (act.equals("start")) {
            var d = p.catalog().events.get(id);
            if (d == null) throw new IllegalArgumentException("Evento desconocido");
            p.events().start(id, a.length > 3 ? Text.duration(a[3]) : d.duration());
          } else if (act.equals("stop")) p.events().stop(id);
          else throw new IllegalArgumentException("Usa start o stop");
        }
        case "reload" -> {
          p.reloadCatalog();
          Text.send(sender, "&aConfiguración recargada");
        }
        case "admin" -> {
          admin(sender, a);
        }
        default -> {
          Player player = player(sender);
          City c = sub.equals("list") ? null : city(player);
          switch (sub) {
            case "menu" -> p.gui().open(player, c, Screen.MAIN, 0);
            case "list" -> p.gui().open(player, null, Screen.CITIES, 0);
            case "border" -> p.visual().border(player, c);
            case "hologram" -> {
              manager(player, c);
              String state = a.length > 1 ? a[1] : "toggle";
              p.cities()
                  .update(
                      player,
                      c,
                      n -> n.hologram = state.equals("on") || !state.equals("off") && !n.hologram,
                      null);
            }
            case "rename", "tag" -> {
              arg(a, 1, sub + " <texto>");
              rename(
                  player,
                  c,
                  String.join(" ", Arrays.copyOfRange(a, 1, a.length)),
                  sub.equals("tag"));
            }
            case "add", "remove" ->
                member(player, c, arg(a, 1, sub + " <jugador>"), sub.equals("add"));
            case "flags" -> p.gui().open(player, c, Screen.FLAGS, 0);
            case "flag" -> {
              manager(player, c);
              String flag = arg(a, 1, "flag <flag> allow|deny [audiencia]");
              if (!p.catalog().defaultFlags.containsKey(flag))
                throw new IllegalArgumentException("Flag desconocida");
              String state = arg(a, 2, "flag <flag> allow|deny [audiencia]");
              if (!state.equalsIgnoreCase("allow") && !state.equalsIgnoreCase("deny"))
                throw new IllegalArgumentException("Usa allow o deny");
              Audience audience = a.length > 3 ? Audience.parse(a[3]) : Audience.NON_MEMBERS;
              p.cities()
                  .update(
                      player,
                      c,
                      n ->
                          n.flags.put(
                              flag, new FlagRule(state.equalsIgnoreCase("allow"), audience)),
                      null);
            }
            case "userflags" ->
                p.gui()
                    .open(
                        player,
                        c,
                        Screen.USERFLAGS,
                        0,
                        null,
                        resolve(arg(a, 1, "userflags <jugador>")));
            case "userflag" -> {
              manager(player, c);
              UUID user = resolve(arg(a, 1, "userflag <jugador> <flag> allow|deny|inherit"));
              String flag = arg(a, 2, "userflag <jugador> <flag> allow|deny|inherit"),
                  state = arg(a, 3, "userflag <jugador> <flag> allow|deny|inherit");
              if (!p.catalog().defaultFlags.containsKey(flag))
                throw new IllegalArgumentException("Flag desconocida");
              if (!Set.of("allow", "deny", "inherit").contains(state.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Usa allow, deny o inherit");
              p.cities()
                  .update(
                      player,
                      c,
                      n -> {
                        var map = n.individual.computeIfAbsent(user, k -> new LinkedHashMap<>());
                        if (state.equalsIgnoreCase("inherit")) map.remove(flag);
                        else map.put(flag, state.equalsIgnoreCase("allow"));
                        if (map.isEmpty()) n.individual.remove(user);
                      },
                      null);
            }
            case "upgrade" -> p.cities().upgrade(player, c, false);
            case "crystals" -> p.gui().open(player, c, Screen.CRYSTALS, 0);
            case "storage" -> p.gui().open(player, c, Screen.STORAGE, 0);
            case "shield" -> p.gui().open(player, c, Screen.SHIELDS, 0);
            case "delete" ->
                p.gui()
                    .confirm(
                        player,
                        c,
                        "&cEliminar ciudad",
                        () -> p.cities().delete(player, p.cities().get(c.id)));
            case "subzone" -> subzone(player, c, a);
            default -> throw new IllegalArgumentException("Comando desconocido. /city help");
          }
        }
      }
    } catch (Exception e) {
      p.message(sender, e.getMessage() == null ? "Operación fallida" : e.getMessage());
    }
    return true;
  }

  private void grant(CommandSender sender, Player target, String kind, String id, int count) {
    p.cities()
        .mint(target, kind, id, count)
        .whenComplete(
            (v, error) ->
                p.main(
                    () -> {
                      if (error != null) p.message(sender, "operation-failed");
                      else
                        p.message(
                            sender,
                            "items-issued",
                            Map.of("count", count, "type", id, "player", target.getName()));
                    }));
  }

  public void rename(Player player, City c, String value, boolean tag) {
    permit(player, tag ? "tag" : "rename");
    manager(player, c);
    if (!tag && !p.cities().level(c).rename())
      throw new IllegalArgumentException("Este nivel no permite cambiar nombre");
    int max = tag ? 12 : 40;
    if (value.isBlank()
        || value.length() > max
        || value.contains("<")
        || value.contains(">")
        || value.contains("§")
        || value.contains("\n"))
      throw new IllegalArgumentException("Nombre inválido (máximo " + max + ")");
    String safe =
        p.catalog().settings.getBoolean("names.allow-colors", false)
            ? value
            : value.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    p.cities()
        .update(
            player,
            c,
            n -> {
              if (tag) n.tag = safe;
              else n.name = safe;
            },
            null);
  }

  public void member(Player player, City c, String name, boolean add) {
    permit(player, add ? "add" : "remove");
    manager(player, c);
    UUID u = resolve(name);
    if (c.isOwner(u)) throw new IllegalArgumentException("El propietario ya tiene acceso");
    if (add && c.members.size() >= p.cities().level(c).maxMembers())
      throw new IllegalArgumentException("Límite de miembros alcanzado");
    p.cities()
        .update(
            player,
            c,
            n -> {
              if (add) n.members.add(u);
              else {
                n.members.remove(u);
                n.individual.remove(u);
              }
            },
            null);
  }

  private void subzone(Player player, City c, String[] a) {
    String act = a.length > 1 ? a[1] : "menu";
    permit(player, "subzone." + act);
    String name = a.length > 2 ? a[2] : null;
    City.Subzone s = p.subzones().find(c, name, player.getLocation());
    switch (act) {
      case "wand" -> {
        manager(player, c);
        p.delivery().queue(player.getUniqueId(), p.subzones().wand());
      }
      case "cancel" -> p.subzones().cancel(player.getUniqueId());
      case "list", "menu", "view" ->
          p.gui()
              .open(
                  player,
                  c,
                  s == null ? Screen.SUBZONES : Screen.SUBZONE,
                  0,
                  s == null ? null : s.id,
                  null);
      case "create" -> p.subzones().create(player, c, arg(a, 2, "subzone create <nombre>"));
      case "delete" -> {
        if (!p.subzones().owner(player, c, s)) throw new IllegalArgumentException("Sin permiso");
        p.gui()
            .confirm(
                player,
                c,
                "&cEliminar subzona " + s.name,
                () ->
                    p.cities()
                        .update(
                            player,
                            p.cities().get(c.id),
                            n -> n.subzones.removeIf(v -> v.id.equals(s.id)),
                            null));
      }
      case "sell" -> {
        if (s == null) throw new IllegalArgumentException("Subzona no encontrada");
        if (a.length > 3)
          p.subzones().sell(player, c, s, Double.parseDouble(a[3]), a.length > 4 ? a[4] : "vault");
        else p.gui().open(player, c, Screen.SUBZONE, 0, s.id, null);
      }
      case "unsell" -> {
        if (!p.subzones().owner(player, c, s)) throw new IllegalArgumentException("Sin permiso");
        p.cities()
            .update(
                player,
                c,
                n -> p.subzones().find(n, s.id.toString(), player.getLocation()).forSale = false,
                null);
      }
      case "buy" -> {
        if (s == null) throw new IllegalArgumentException("Subzona no encontrada");
        p.gui()
            .confirm(
                player,
                c,
                "&aComprar " + s.name,
                () ->
                    p.subzones()
                        .buy(
                            player,
                            p.cities().get(c.id),
                            p.subzones()
                                .find(
                                    p.cities().get(c.id), s.id.toString(), player.getLocation())));
      }
      case "flags" -> {
        if (s == null) throw new IllegalArgumentException("Subzona no encontrada");
        p.gui().open(player, c, Screen.FLAGS, 0, s.id, null);
      }
      case "add", "remove" -> {
        if (!p.subzones().owner(player, c, s)) throw new IllegalArgumentException("Sin permiso");
        UUID u = resolve(arg(a, 3, "subzone " + act + " <subzona> <jugador>"));
        p.cities()
            .update(
                player,
                c,
                n -> {
                  var target = p.subzones().find(n, s.id.toString(), player.getLocation());
                  if (act.equals("add")) target.members.add(u);
                  else {
                    target.members.remove(u);
                    target.individual.remove(u);
                  }
                },
                null);
      }
      case "userflag" -> {
        if (!p.subzones().owner(player, c, s)) throw new IllegalArgumentException("Sin permiso");
        UUID u = resolve(arg(a, 3, "subzone userflag <subzona> <jugador> <flag> allow|deny"));
        String f = arg(a, 4, "subzone userflag <subzona> <jugador> <flag> allow|deny"),
            state = arg(a, 5, "subzone userflag <subzona> <jugador> <flag> allow|deny");
        if (!p.catalog().defaultFlags.containsKey(f) || !Set.of("allow", "deny").contains(state))
          throw new IllegalArgumentException("Flag o estado inválido");
        p.cities()
            .update(
                player,
                c,
                n ->
                    p.subzones()
                        .find(n, s.id.toString(), player.getLocation())
                        .individual
                        .computeIfAbsent(u, k -> new LinkedHashMap<>())
                        .put(f, state.equals("allow")),
                null);
      }
      default -> throw new IllegalArgumentException("Subcomando desconocido");
    }
  }

  private void admin(CommandSender sender, String[] a) {
    if (!sender.hasPermission("terranova.admin"))
      throw new IllegalArgumentException("Sin permiso de administración");
    String act = arg(a, 1, "admin list|menu|owner|upgrade|delete <id> [jugador]");
    if (act.equals("finance")) {
      String action = a.length > 2 ? a[2] : "list";
      if (action.equals("list")) {
        p.repository()
            .money()
            .whenComplete(
                (rows, error) ->
                    p.main(
                        () -> {
                          if (error != null) p.failure(error);
                          else
                            for (var row : rows)
                              Text.send(
                                  sender,
                                  "&f"
                                      + row.id()
                                      + " &8│ &e"
                                      + row.status()
                                      + " &8│ &f"
                                      + row.player()
                                      + " "
                                      + row.amount()
                                      + " "
                                      + row.currency());
                        }));
        return;
      }
      String idText = arg(a, 3, "admin finance retry|ack <id>");
      if (!Set.of("retry", "ack").contains(action))
        throw new IllegalArgumentException("Usa list, retry o ack");
      p.moneyRecovery().resolve(UUID.fromString(idText), action.equals("retry"));
      return;
    }
    if (act.equals("list")) {
      for (City c : p.cities().all()) Text.send(sender, "&f" + c.id + " &8│ &a" + c.name);
      return;
    }
    UUID id = UUID.fromString(arg(a, 2, "admin " + act + " <id>"));
    City c = p.cities().get(id);
    if (c == null) throw new IllegalArgumentException("Ciudad no encontrada");
    Player actor = player(sender);
    switch (act) {
      case "menu" -> p.gui().open(actor, c, Screen.MAIN, 0);
      case "owner" -> {
        UUID u = resolve(arg(a, 3, "admin owner <id> <jugador>"));
        p.cities()
            .update(
                actor,
                c,
                n -> {
                  n.members.remove(u);
                  n.owner = u;
                },
                null);
      }
      case "upgrade" -> p.cities().upgrade(actor, c, true);
      case "delete" ->
          p.gui()
              .confirm(
                  actor,
                  c,
                  "&cEliminar " + c.name,
                  () -> p.cities().delete(actor, p.cities().get(c.id)));
      default -> throw new IllegalArgumentException("Acción administrativa desconocida");
    }
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
    List<String> out = new ArrayList<>();
    if (a.length == 1)
      out.addAll(
          List.of(
              "help",
              "claim",
              "menu",
              "list",
              "border",
              "hologram",
              "rename",
              "tag",
              "add",
              "remove",
              "flags",
              "flag",
              "userflags",
              "userflag",
              "upgrade",
              "crystals",
              "storage",
              "shield",
              "delete",
              "subzone",
              "give",
              "crystal",
              "event",
              "reload",
              "admin"));
    else if (a[0].equals("give") && a.length == 3) out.addAll(p.catalog().types.keySet());
    else if (a[0].equals("crystal") && a.length == 4) out.addAll(p.catalog().crystals.keySet());
    else if (a[0].equals("event")) {
      if (a.length == 2) out.addAll(List.of("start", "stop"));
      if (a.length == 3) out.addAll(p.catalog().events.keySet());
    } else if (a[0].equals("subzone") && a.length == 2)
      out.addAll(
          List.of(
              "wand",
              "create",
              "delete",
              "list",
              "menu",
              "cancel",
              "sell",
              "unsell",
              "buy",
              "flags",
              "add",
              "remove",
              "userflag"));
    else if (a[0].equals("flag")) {
      if (a.length == 2) out.addAll(p.catalog().defaultFlags.keySet());
      if (a.length == 3) out.addAll(List.of("allow", "deny"));
      if (a.length == 4) out.addAll(Arrays.stream(Audience.values()).map(Enum::name).toList());
    } else for (Player player : Bukkit.getOnlinePlayers()) out.add(player.getName());
    String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
    return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }
}
