package dev.marco035xd.terranova.audit;

import java.lang.reflect.*;
import java.util.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.*;

/** Simulated players only; world, items, events, inventory menus and JDBC are real Paper. */
final class FakePlayer implements InvocationHandler {
  final UUID id;
  final String name;
  Location location;
  ItemStack cursor;
  Inventory opened;
  String actionbar = "";
  boolean flight;
  boolean online = true;
  final List<String> messages = new ArrayList<>();
  final Map<PotionEffectType, PotionEffect> potions = new HashMap<>();
  final ItemStack[] contents = new ItemStack[41];
  final Player player;
  final PlayerInventory inventory;

  FakePlayer(UUID id, String name, Location location) {
    this.id = id;
    this.name = name;
    this.location = location;
    player =
        (Player)
            Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[] {Player.class}, this);
    inventory =
        (PlayerInventory)
            Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[] {PlayerInventory.class},
                this::inventory);
  }

  public Object invoke(Object proxy, Method m, Object[] a) {
    return switch (m.getName()) {
      case "getUniqueId" -> id;
      case "getName", "getDisplayName" -> name;
      case "getServer" -> Bukkit.getServer();
      case "isOnline", "isValid" -> online;
      case "sendMessage" -> {
        for (Object value : a) {
          if (value instanceof String line) messages.add(line);
          else if (value instanceof String[] lines) messages.addAll(Arrays.asList(lines));
        }
        yield null;
      }
      case "getWorld" -> location.getWorld();
      case "getLocation" -> location.clone();
      case "getInventory" -> inventory;
      case "hasPermission" ->
          String.valueOf(a[0]).startsWith("terranova.command.")
              || String.valueOf(a[0]).equals("terranova.raid.steal")
              || String.valueOf(a[0]).equals("terranova.create");
      case "getEffectivePermissions" -> Set.of();
      case "getGameMode" -> GameMode.SURVIVAL;
      case "getAllowFlight", "isFlying" -> flight;
      case "setAllowFlight" -> {
        flight = (boolean) a[0];
        yield null;
      }
      case "getPotionEffect" -> potions.get(a[0]);
      case "hasPotionEffect" -> potions.containsKey(a[0]);
      case "addPotionEffect" -> {
        PotionEffect e = (PotionEffect) a[0];
        potions.put(e.getType(), e);
        yield true;
      }
      case "removePotionEffect" -> {
        potions.remove(a[0]);
        yield null;
      }
      case "getActivePotionEffects" -> potions.values();
      case "getItemOnCursor" -> cursor;
      case "setItemOnCursor" -> {
        cursor = (ItemStack) a[0];
        yield null;
      }
      case "openInventory" -> {
        opened = (Inventory) a[0];
        yield view();
      }
      case "getOpenInventory" -> view();
      case "closeInventory" -> {
        opened = null;
        yield null;
      }
      case "spigot" ->
          new Player.Spigot() {
            @Override
            public void sendMessage(ChatMessageType type, BaseComponent... text) {
              actionbar = BaseComponent.toLegacyText(text);
            }
          };
      case "equals" -> proxy == a[0];
      case "hashCode" -> id.hashCode();
      case "toString" -> name;
      default -> zero(m.getReturnType());
    };
  }

  private Object inventory(Object proxy, Method m, Object[] a) {
    return switch (m.getName()) {
      case "getHolder" -> player;
      case "getType" -> InventoryType.PLAYER;
      case "getSize" -> 41;
      case "getStorageContents" -> Arrays.copyOf(contents, 36);
      case "getContents" -> contents.clone();
      case "firstEmpty" -> {
        int slot = -1;
        for (int i = 0; i < 36; i++)
          if (contents[i] == null || contents[i].getType().isAir()) {
            slot = i;
            break;
          }
        yield slot;
      }
      case "getItem" -> contents[(int) a[0]];
      case "setItem" -> {
        contents[(int) a[0]] = (ItemStack) a[1];
        yield null;
      }
      case "getItemInMainHand" -> contents[0] == null ? new ItemStack(Material.AIR) : contents[0];
      case "getLocation" -> location;
      case "getViewers" -> List.of(player);
      case "equals" -> proxy == a[0];
      case "hashCode" -> System.identityHashCode(proxy);
      default -> zero(m.getReturnType());
    };
  }

  InventoryView view() {
    return (InventoryView)
        Proxy.newProxyInstance(
            InventoryView.class.getClassLoader(),
            new Class[] {InventoryView.class},
            (proxy, m, a) ->
                switch (m.getName()) {
                  case "getTopInventory" -> opened;
                  case "getBottomInventory" -> inventory;
                  case "getPlayer" -> player;
                  case "getType" -> InventoryType.CHEST;
                  case "getCursor" -> cursor;
                  case "setCursor" -> {
                    cursor = (ItemStack) a[0];
                    yield null;
                  }
                  case "countSlots" -> opened.getSize() + 36;
                  case "getInventory" -> (int) a[0] < opened.getSize() ? opened : inventory;
                  case "convertSlot" ->
                      (int) a[0] < opened.getSize() ? (int) a[0] : (int) a[0] - opened.getSize();
                  case "getItem" ->
                      (int) a[0] < opened.getSize()
                          ? opened.getItem((int) a[0])
                          : inventory.getItem((int) a[0] - opened.getSize());
                  case "getSlotType" -> InventoryType.SlotType.CONTAINER;
                  case "getTitle", "getOriginalTitle" -> "Audit";
                  default -> zero(m.getReturnType());
                });
  }

  private static Object zero(Class<?> t) {
    if (t == boolean.class) return false;
    if (t == int.class) return 0;
    if (t == long.class) return 0L;
    if (t == double.class) return 0d;
    if (t == float.class) return 0f;
    if (t == short.class) return (short) 0;
    if (t == byte.class) return (byte) 0;
    if (t == char.class) return '\0';
    return null;
  }
}
