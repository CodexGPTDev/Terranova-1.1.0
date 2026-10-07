package dev.marco035xd.terranova.listener;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

/** Listeners translate Bukkit events to one decision service; they do not implement flag policy. */
public final class ProtectionListener implements Listener {
  private final TerranovaPlugin p;
  private final Set<UUID> raidBreaking = new HashSet<>();

  public ProtectionListener(TerranovaPlugin p) {
    this.p = p;
  }

  private boolean allow(Player actor, Location at, String flag) {
    return p.decisions().decide(actor, at, flag).allowed();
  }

  private Player actor(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player)
      return player;
    if (entity instanceof TNTPrimed t && t.getSource() instanceof Player player) return player;
    if (entity instanceof Tameable tame && tame.getOwner() instanceof Player player) return player;
    return null;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    var identity = p.items().identity(e.getItemInHand());
    if (identity != null && identity.kind().equals("core")) {
      try {
        if (!e.getPlayer().hasPermission("terranova.create"))
          throw new IllegalArgumentException("Sin permiso para crear ciudades");
        p.cities().validateCreation(e.getPlayer(), e.getBlockPlaced(), identity);
      } catch (RuntimeException error) {
        e.setCancelled(true);
        p.message(e.getPlayer(), error.getMessage());
      }
      return;
    }
    if (!p.decisions().canPlace(e.getPlayer(), e.getBlock().getLocation(), e.getBlock().getType()))
      e.setCancelled(true);
    if (e.getBlock().getType() == Material.TNT
        && !allow(e.getPlayer(), e.getBlock().getLocation(), "tnt")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void placedCore(BlockPlaceEvent e) {
    var identity = p.items().identity(e.getItemInHand());
    if (identity == null || !identity.kind().equals("core")) return;
    try {
      p.cities().create(e.getPlayer(), e.getBlockPlaced(), identity);
    } catch (RuntimeException error) {
      e.getBlockReplacedState().update(true, false);
      var type = p.catalog().types.get(identity.id());
      if (type != null)
        p.delivery()
            .queue(e.getPlayer().getUniqueId(), p.cities().coreItem(type, identity.token()));
      p.message(e.getPlayer(), error.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void multiPlace(BlockMultiPlaceEvent e) {
    for (BlockState state : e.getReplacedBlockStates())
      if (!p.decisions().canPlace(e.getPlayer(), state.getLocation(), state.getType())) {
        e.setCancelled(true);
        return;
      }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    City c = p.cities().at(e.getBlock().getLocation());
    if (p.cities().core(c, e.getBlock())) {
      e.setCancelled(true);
      p.message(e.getPlayer(), "core-protected");
      return;
    }
    if (!p.decisions().canBreak(e.getPlayer(), e.getBlock().getLocation(), e.getBlock())) {
      e.setCancelled(true);
      return;
    }
    if (c != null
        && p.hits().enabled(c, e.getPlayer(), e.getBlock())
        && !raidBreaking.contains(e.getPlayer().getUniqueId())) {
      e.setCancelled(true);
      raidHit(e.getPlayer(), c, e.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void damage(BlockDamageEvent e) {
    City c = p.cities().at(e.getBlock().getLocation());
    if (p.cities().core(c, e.getBlock())
        || !p.decisions().canBreak(e.getPlayer(), e.getBlock().getLocation(), e.getBlock())) {
      e.setCancelled(true);
      return;
    }
    if (c != null && p.hits().enabled(c, e.getPlayer(), e.getBlock())) {
      e.setCancelled(true);
      raidHit(e.getPlayer(), c, e.getBlock());
    }
  }

  private void raidHit(Player player, City c, Block b) {
    if (!p.hits().hit(player, c, b)) return;
    raidBreaking.add(player.getUniqueId());
    try {
      BlockBreakEvent test = new BlockBreakEvent(b, player);
      p.getServer().getPluginManager().callEvent(test);
      if (test.isCancelled()) return;
      if (p.hits().dropItems(c)) {
        b.breakNaturally(player.getInventory().getItemInMainHand());
      } else b.setType(Material.AIR);
    } finally {
      raidBreaking.remove(player.getUniqueId());
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void interact(PlayerInteractEvent e) {
    Player player = e.getPlayer();
    Block b = e.getClickedBlock();
    Items.Identity item = p.items().identity(e.getItem());
    if (item != null && item.kind().equals("wand") && b != null) {
      e.setCancelled(true);
      p.subzones().select(player, b.getLocation(), e.getAction() == Action.LEFT_CLICK_BLOCK);
      return;
    }
    if (b != null) {
      City c = p.cities().at(b.getLocation());
      if (p.cities().core(c, b) && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
        e.setCancelled(true);
        if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND)
          try {
            p.gui().open(player, c, dev.marco035xd.terranova.gui.GuiService.Screen.MAIN, 0);
          } catch (RuntimeException error) {
            p.message(player, error.getMessage());
          }
        return;
      }
    }
    if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) return;
    if (e.getAction() == Action.PHYSICAL && b != null) {
      if (!allow(
          player,
          b.getLocation(),
          b.getType() == Material.FARMLAND || b.getType() == Material.TURTLE_EGG
              ? "block-trampling"
              : "use")) e.setCancelled(true);
      return;
    }
    if (e.getItem() != null && !p.decisions().canUseItem(player, player.getLocation(), e.getItem()))
      e.setCancelled(true);
    if (b != null && !e.isCancelled()) {
      String flag =
          b.getState() instanceof Container
              ? "chest-access"
              : b.getType() == Material.ANVIL
                      || b.getType() == Material.CHIPPED_ANVIL
                      || b.getType() == Material.DAMAGED_ANVIL
                  ? "use-anvil"
                  : "interact";
      if (!allow(player, b.getLocation(), flag)) e.setCancelled(true);
    }
    if (e.getItem() != null
        && Set.of(Material.FLINT_AND_STEEL, Material.FIRE_CHARGE).contains(e.getItem().getType())
        && !allow(player, b == null ? player.getLocation() : b.getLocation(), "lighter"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void useEntity(PlayerInteractEntityEvent e) {
    String flag =
        e.getRightClicked() instanceof Villager
            ? "villager-trading"
            : e.getRightClicked() instanceof ItemFrame
                ? "item-frame-rotation"
                : e.getRightClicked() instanceof Vehicle ? "vehicle-use" : "interact";
    if (!allow(e.getPlayer(), e.getRightClicked().getLocation(), flag)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void armor(PlayerArmorStandManipulateEvent e) {
    if (!allow(e.getPlayer(), e.getRightClicked().getLocation(), "interact")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void bucket(PlayerBucketEmptyEvent e) {
    Location at = e.getBlockClicked().getRelative(e.getBlockFace()).getLocation();
    if (!p.decisions().canPlace(e.getPlayer(), at, e.getBucket())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void bucketFill(PlayerBucketFillEvent e) {
    if (!allow(e.getPlayer(), e.getBlockClicked().getLocation(), "block-break"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void consume(PlayerItemConsumeEvent e) {
    if (!p.decisions().canUseItem(e.getPlayer(), e.getPlayer().getLocation(), e.getItem()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void projectile(ProjectileLaunchEvent e) {
    Player player = actor(e.getEntity());
    if (player == null) return;
    Material m =
        e.getEntity() instanceof EnderPearl
            ? Material.ENDER_PEARL
            : e.getEntity() instanceof WindCharge ? Material.WIND_CHARGE : null;
    if (m != null
        && !p.decisions().canUseItem(player, e.getEntity().getLocation(), new ItemStack(m)))
      e.setCancelled(true);
    if (m == Material.WIND_CHARGE && !allow(player, e.getEntity().getLocation(), "wind-charge"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (e.getTo() != null
        && !p.decisions().canTeleport(e.getPlayer(), e.getFrom(), e.getTo(), e.getCause()))
      e.setCancelled(true);
    else p.main(() -> p.effects().refresh(e.getPlayer()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    Location a = e.getFrom(), b = e.getTo();
    if (b == null
        || a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ()
            && a.getWorld() == b.getWorld()) return;
    City from = p.cities().at(a), to = p.cities().at(b);
    City.Subzone
        fs = from == null ? null : from.subzone(a.getBlockX(), a.getBlockY(), a.getBlockZ()),
        ts = to == null ? null : to.subzone(b.getBlockX(), b.getBlockY(), b.getBlockZ());
    if (from != to || fs != ts) {
      if (!allow(e.getPlayer(), b, "entry") || !allow(e.getPlayer(), a, "exit")) {
        e.setCancelled(true);
        return;
      }
      p.main(() -> p.effects().refresh(e.getPlayer()));
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void damage(EntityDamageByEntityEvent e) {
    Player player = actor(e.getDamager());
    if (player != null) {
      if (!p.decisions().canDamage(player, e.getEntity())
          || e.getEntity() instanceof Player && !allow(player, e.getDamager().getLocation(), "pvp"))
        e.setCancelled(true);
    } else if (e.getEntity() instanceof Player target
        && !allow(target, target.getLocation(), "mob-damage")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void naturalDamage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player player
        && e.getCause() == EntityDamageEvent.DamageCause.FALL
        && !allow(player, player.getLocation(), "fall-damage")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void splash(PotionSplashEvent e) {
    Player source = actor(e.getPotion());
    for (LivingEntity target : e.getAffectedEntities())
      if (!allow(source, target.getLocation(), "potion-splash")) e.setIntensity(target, 0);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void cloud(AreaEffectCloudApplyEvent e) {
    Player source = e.getEntity().getSource() instanceof Player player ? player : null;
    e.getAffectedEntities()
        .removeIf(target -> !allow(source, target.getLocation(), "potion-splash"));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void entityExplosion(EntityExplodeEvent e) {
    Player source = actor(e.getEntity());
    String flag =
        e.getEntity() instanceof Creeper
            ? "creeper-explosion"
            : e.getEntity() instanceof Wither || e.getEntity() instanceof WitherSkull
                ? "wither-damage"
                : e.getEntity() instanceof Fireball
                    ? "ghast-fireball"
                    : e.getEntity() instanceof TNTPrimed ? "tnt" : "other-explosion";
    e.blockList()
        .removeIf(
            b ->
                p.cities().core(p.cities().at(b.getLocation()), b)
                    || !allow(source, b.getLocation(), flag));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void explosion(BlockExplodeEvent e) {
    e.blockList()
        .removeIf(
            b ->
                p.cities().core(p.cities().at(b.getLocation()), b)
                    || !allow(null, b.getLocation(), "other-explosion"));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    if (piston(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void retract(BlockPistonRetractEvent e) {
    if (piston(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
  }

  private boolean piston(Block base, List<Block> blocks, BlockFace direction) {
    if (!allow(null, base.getLocation(), "pistons")) return true;
    City origin = p.cities().at(base.getLocation());
    for (Block b : blocks) {
      Block to = b.getRelative(direction);
      City source = p.cities().at(b.getLocation()), target = p.cities().at(to.getLocation());
      if (p.cities().core(source, b)
          || p.cities().core(target, to)
          || !allow(null, b.getLocation(), "pistons")
          || !allow(null, to.getLocation(), "pistons")) return true;
      if (!sameRegion(b.getLocation(), to.getLocation())
          || !sameRegion(base.getLocation(), to.getLocation()))
        if (!allow(null, b.getLocation(), "pistons-out")
            || !allow(null, to.getLocation(), "pistons-out")
            || !allow(null, base.getLocation(), "pistons-out")) return true;
    }
    return false;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void hopper(InventoryMoveItemEvent e) {
    Location a = e.getSource().getLocation(), b = e.getDestination().getLocation();
    if (a == null || b == null) return;
    if (!sameRegion(a, b) && (!allow(null, a, "chest-access") || !allow(null, b, "chest-access")))
      e.setCancelled(true);
  }

  private boolean sameRegion(Location a, Location b) {
    City x = p.cities().at(a), y = p.cities().at(b);
    if (x != y) return false;
    return x == null
        || x.subzone(a.getBlockX(), a.getBlockY(), a.getBlockZ())
            == x.subzone(b.getBlockX(), b.getBlockY(), b.getBlockZ());
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void flow(BlockFromToEvent e) {
    String f = e.getBlock().getType() == Material.LAVA ? "lava-flow" : "water-flow";
    if (!allow(null, e.getBlock().getLocation(), f)
        || !allow(null, e.getToBlock().getLocation(), f)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void burn(BlockBurnEvent e) {
    if (p.cities().core(p.cities().at(e.getBlock().getLocation()), e.getBlock())
        || !allow(null, e.getBlock().getLocation(), "fire-spread")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void ignite(BlockIgniteEvent e) {
    String f = e.getCause() == BlockIgniteEvent.IgniteCause.LAVA ? "lava-fire" : "fire-spread";
    if (!allow(e.getPlayer(), e.getBlock().getLocation(), e.getPlayer() == null ? f : "lighter"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void spread(BlockSpreadEvent e) {
    String f =
        e.getSource().getType() == Material.FIRE
            ? "fire-spread"
            : e.getSource().getType().name().contains("MUSHROOM")
                ? "mushroom-growth"
                : e.getSource().getType().name().contains("SCULK")
                    ? "sculk-growth"
                    : "grass-growth";
    if (!allow(null, e.getBlock().getLocation(), f)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void form(BlockFormEvent e) {
    String f =
        e.getNewState().getType() == Material.SNOW
            ? "snow-fall"
            : e.getNewState().getType() == Material.ICE
                ? "ice-form"
                : e.getNewState().getType() == Material.FROSTED_ICE
                    ? "frosted-ice-form"
                    : "rock-growth";
    Player actor =
        e instanceof EntityBlockFormEvent entity && entity.getEntity() instanceof Player player
            ? player
            : null;
    if (!allow(actor, e.getBlock().getLocation(), f)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void fade(BlockFadeEvent e) {
    String name = e.getBlock().getType().name();
    String f =
        name.equals("FROSTED_ICE")
            ? "frosted-ice-melt"
            : name.contains("ICE")
                ? "ice-melt"
                : name.contains("SNOW")
                    ? "snow-melt"
                    : name.contains("CORAL")
                        ? "coral-fade"
                        : name.contains("COPPER")
                            ? "copper-fade"
                            : name.equals("FARMLAND") ? "soil-dry" : "crop-growth";
    if (!allow(null, e.getBlock().getLocation(), f)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void decay(LeavesDecayEvent e) {
    if (!allow(null, e.getBlock().getLocation(), "leaf-decay")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void grow(BlockGrowEvent e) {
    if (!allow(null, e.getBlock().getLocation(), "crop-growth")) {
      e.setCancelled(true);
      return;
    }
    City city = p.cities().at(e.getBlock().getLocation());
    if (city != null
        && e.getNewState().getBlockData() instanceof org.bukkit.block.data.Ageable age) {
      double mult = p.effects().crop(city);
      if (mult > 1) {
        int bonus = (int) Math.min(age.getMaximumAge(), Math.floor(mult - 1));
        age.setAge(Math.min(age.getMaximumAge(), age.getAge() + bonus));
        e.getNewState().setBlockData(age);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void structure(StructureGrowEvent e) {
    if (!allow(e.getPlayer(), e.getLocation(), "crop-growth")) e.setCancelled(true);
    e.getBlocks()
        .removeIf(
            b ->
                !allow(e.getPlayer(), b.getLocation(), "crop-growth")
                    || p.cities().core(p.cities().at(b.getLocation()), b.getBlock()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void moisture(MoistureChangeEvent e) {
    if (!allow(null, e.getBlock().getLocation(), "soil-dry")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void entityBlock(EntityChangeBlockEvent e) {
    String flag =
        e.getEntity() instanceof Enderman
            ? "enderman-grief"
            : e.getEntity() instanceof Ravager
                ? "ravager-grief"
                : e.getEntity() instanceof Snowman
                    ? "snowman-trails"
                    : e.getEntity() instanceof EnderDragon
                        ? "enderdragon-block-damage"
                        : "block-break";
    if (p.cities().core(p.cities().at(e.getBlock().getLocation()), e.getBlock())
        || !allow(actor(e.getEntity()), e.getBlock().getLocation(), flag)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void spawn(CreatureSpawnEvent e) {
    if (!allow(null, e.getLocation(), "mob-spawning")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void fishing(PlayerFishEvent e) {
    if (!allow(e.getPlayer(), e.getHook().getLocation(), "fishing-rod")
        || e.getCaught() != null
            && !allow(e.getPlayer(), e.getCaught().getLocation(), "fishing-rod"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void bed(PlayerBedEnterEvent e) {
    if (!allow(e.getPlayer(), e.getBed().getLocation(), "sleep")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void hanging(HangingBreakByEntityEvent e) {
    if (!allow(actor(e.getRemover()), e.getEntity().getLocation(), "block-break"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void hangingPlace(HangingPlaceEvent e) {
    if (!allow(e.getPlayer(), e.getBlock().getLocation(), "block-place")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void vehicleDamage(VehicleDamageEvent e) {
    if (!allow(actor(e.getAttacker()), e.getVehicle().getLocation(), "vehicle-destroy"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void vehicleEnter(VehicleEnterEvent e) {
    if (e.getEntered() instanceof Player player
        && !allow(player, e.getVehicle().getLocation(), "vehicle-use")) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void potionChanged(EntityPotionEffectEvent e) {
    if (e.getEntity() instanceof Player player)
      p.effects().externalPotionChange(player, e.getModifiedType());
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    p.main(
        () -> {
          p.effects().refresh(e.getPlayer());
          p.delivery().drain(e.getPlayer());
        });
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    p.effects().forget(e.getPlayer());
    UUID u = e.getPlayer().getUniqueId();
    p.gui().forget(u);
    p.subzones().cancel(u);
    p.visual().forget(u);
    p.hits().forget(u);
    p.delivery().forget(u);
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    p.effects().refresh(e.getPlayer());
  }

  @EventHandler
  public void chunk(ChunkLoadEvent e) {
    p.main(() -> p.visual().cleanupChunk(e.getChunk()));
  }
}
