package dev.marco035xd.terranova;

import dev.marco035xd.terranova.api.*;
import dev.marco035xd.terranova.command.*;
import dev.marco035xd.terranova.config.*;
import dev.marco035xd.terranova.crystal.*;
import dev.marco035xd.terranova.gui.*;
import dev.marco035xd.terranova.hook.*;
import dev.marco035xd.terranova.listener.*;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.service.*;
import dev.marco035xd.terranova.storage.*;
import dev.marco035xd.terranova.util.*;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class TerranovaPlugin extends JavaPlugin {
  private ConfigCatalog catalog;
  private JdbcRepository repository;
  private Items items;
  private CityService cities;
  private DeliveryService delivery;
  private EconomyService economy;
  private MoneyRecoveryService moneyRecovery;
  private EventService events;
  private ProtectionDecisionService decisions;
  private RaidHitService hits;
  private CrystalEffectEngine effects;
  private SubzoneService subzones;
  private VisualService visual;
  private GuiService gui;
  private CityCommand commands;
  private boolean stopping;

  @Override
  public void onEnable() {
    try {
      for (String resource :
          List.of(
              "config.yml",
              "messages.yml",
              "gui.yml",
              "flags.yml",
              "city-types/classic.yml",
              "city-types/fortress.yml",
              "crystals/guardian.yml",
              "crystals/skybound.yml",
              "crystals/aura-scholar.yml",
              "crystals/raid-lockdown.yml",
              "crystals/harvest.yml",
              "crystals/bastion.yml",
              "events/raid.yml",
              "events/raid-weekend.yml",
              "events/booster.yml",
              "shields/guardian-30m.yml",
              "shields/guardian-2h.yml"))
        if (!new File(getDataFolder(), resource).exists()) saveResource(resource, false);
      mergeMissingMessages();
      catalog = new ConfigCatalog(this);
      items = new Items(this);
      repository =
          new JdbcRepository(catalog.settings.getConfigurationSection("database"), getDataFolder());
      delivery = new DeliveryService(this);
      economy = new EconomyService(this);
      moneyRecovery = new MoneyRecoveryService(this);
      cities = new CityService(this);
      events = new EventService(this);
      decisions = new ProtectionDecisionService(this);
      hits = new RaidHitService(this);
      effects = new CrystalEffectEngine(this);
      subzones = new SubzoneService(this);
      visual = new VisualService(this);
      gui = new GuiService(this);
      commands = new CityCommand(this);
      cities.load();
      events.load();
      Objects.requireNonNull(getCommand("city")).setExecutor(commands);
      getCommand("city").setTabCompleter(commands);
      getServer().getPluginManager().registerEvents(gui, this);
      getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
      new AuraSkillsHook(this);
      new AdvancedEnchantmentsHook(this);
      if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null)
        new PlaceholderHook(this).register();
      TerranovaApi api =
          new TerranovaApi() {
            private CityView view(City c) {
              return new CityView(
                  c.id,
                  c.owner,
                  c.name,
                  c.level,
                  c.bounds,
                  Set.copyOf(c.members),
                  Map.copyOf(c.flags),
                  c.shieldActive(System.currentTimeMillis()));
            }

            public Optional<CityView> cityAt(Location l) {
              return Optional.ofNullable(cities.at(l)).map(this::view);
            }

            public Collection<CityView> cities() {
              return cities.all().stream().map(this::view).toList();
            }

            public ProtectionDecisionService.Decision decision(Player a, Location l, String f) {
              return decisions.decide(a, l, f);
            }

            public CrystalEffectRegistry crystalRegistry() {
              return effects.registry();
            }

            public void registerGuard(ProtectionDecisionService.ExternalGuard g) {
              decisions.register(g);
            }

            public void registerEventType(String t, EventService.EventFactory f) {
              events.register(t, f);
            }
          };
      getServer()
          .getServicesManager()
          .register(TerranovaApi.class, api, this, ServicePriority.Normal);
      for (World world : Bukkit.getWorlds())
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) visual.cleanupChunk(chunk);
      effects.refreshAll();
      long visualInterval =
          Math.max(5, catalog.settings.getLong("core-effects.particles.interval", 20));
      getServer()
          .getScheduler()
          .runTaskTimer(
              this,
              () -> {
                visual.tick();
                subzones.tick();
              },
              visualInterval,
              visualInterval);
      getServer()
          .getScheduler()
          .runTaskTimer(
              this,
              () -> {
                events.tick();
                hits.clean(System.currentTimeMillis());
                for (City c : cities.all())
                  if (!c.shield.isEmpty()
                      && !c.shieldActive(System.currentTimeMillis())
                      && !cities.busy(c.id))
                    cities.update(
                        null,
                        c,
                        n -> {
                          n.shield = "";
                          n.shieldUntil = 0;
                        },
                        null);
                for (Player player : Bukkit.getOnlinePlayers()) delivery.drain(player);
              },
              20,
              20);
      getServer().getScheduler().runTaskTimer(this, effects::renew, 60, 60);
      getServer().getScheduler().runTaskTimer(this, gui::refreshAll, 100, 100);
      getServer().getScheduler().runTaskTimer(this, moneyRecovery::drain, 20, 200);
      getLogger()
          .info(
              "Terranova "
                  + getDescription().getVersion()
                  + ": "
                  + cities.all().size()
                  + " ciudades, "
                  + catalog.crystals.size()
                  + " cristales, "
                  + catalog.events.size()
                  + " eventos");
    } catch (Exception error) {
      getLogger().log(Level.SEVERE, "No se pudo iniciar Terranova", error);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  private void mergeMissingMessages() throws Exception {
    File file = new File(getDataFolder(), "messages.yml");
    YamlConfiguration live = YamlConfiguration.loadConfiguration(file);
    YamlConfiguration defaults;
    try (var reader =
        new InputStreamReader(
            Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8)) {
      defaults = YamlConfiguration.loadConfiguration(reader);
    }
    boolean changed = false;
    for (String key : defaults.getKeys(true)) {
      if (!defaults.isConfigurationSection(key) && !live.contains(key)) {
        live.set(key, defaults.get(key));
        changed = true;
      }
    }
    if (changed) live.save(file);
  }

  @Override
  public void onDisable() {
    stopping = true;
    if (gui != null)
      for (Player player : Bukkit.getOnlinePlayers())
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiService.Menu)
          player.closeInventory();
    if (effects != null) effects.close();
    if (events != null) events.close();
    if (visual != null) visual.close();
    getServer().getServicesManager().unregisterAll(this);
    if (repository != null)
      try {
        repository.close();
      } catch (Exception e) {
        getLogger().log(Level.SEVERE, "No se pudo cerrar la persistencia", e);
      }
  }

  public void main(Runnable task) {
    if (stopping) return;
    if (Bukkit.isPrimaryThread()) task.run();
    else if (isEnabled()) getServer().getScheduler().runTask(this, task);
  }

  public void failure(Throwable error) {
    getLogger().log(Level.SEVERE, "Operación persistente fallida", error);
  }

  public void message(CommandSender sender, String key) {
    message(sender, key, Map.of());
  }

  public void message(CommandSender sender, String key, Map<String, ?> variables) {
    if (sender == null) return;
    String message = catalog == null ? key : catalog.messages.getString(key, key);
    Text.send(
        sender,
        (catalog == null ? "" : catalog.messages.getString("prefix", ""))
            + Text.replace(message, variables));
  }

  public void apiEvent(UUID city) {
    getServer().getPluginManager().callEvent(new CityChangeEvent(city));
  }

  public MoneyRecoveryService moneyRecovery() {
    return moneyRecovery;
  }

  public void reloadCatalog() throws Exception {
    ConfigCatalog next = new ConfigCatalog(this);
    for (City c : cities.all()) {
      var type = next.types.get(c.type);
      if (type == null || !type.levels().containsKey(c.level))
        throw new IllegalArgumentException("No elimines tipos/niveles utilizados");
      var old = cities.level(c);
      var current = type.level(c.level);
      if (old.radius() != current.radius()
          || current.chestRows() * 9
              < c.storage.keySet().stream().max(Integer::compareTo).orElse(-1) + 1
          || current.crystalSlots()
              < c.crystals.keySet().stream().max(Integer::compareTo).orElse(-1) + 1)
        throw new IllegalArgumentException(
            "No cambies radios activos ni reduzcas capacidad utilizada; aplica niveles nuevos");
      for (var crystal : c.crystals.values())
        if (!next.crystals.containsKey(crystal.id()))
          throw new IllegalArgumentException("No elimines cristales instalados");
    }
    for (var event : events.all())
      if (!next.events.containsKey(event.id()))
        throw new IllegalArgumentException("Detén el evento antes de eliminar su YAML");
    catalog = next;
    economy = new EconomyService(this);
    events.reload();
    effects.refreshAll();
    for (City c : cities.all()) visual.refresh(c);
    gui.refreshAll();
  }

  public ConfigCatalog catalog() {
    return catalog;
  }

  public JdbcRepository repository() {
    return repository;
  }

  public Items items() {
    return items;
  }

  public CityService cities() {
    return cities;
  }

  public DeliveryService delivery() {
    return delivery;
  }

  public EconomyService economy() {
    return economy;
  }

  public EventService events() {
    return events;
  }

  public ProtectionDecisionService decisions() {
    return decisions;
  }

  public RaidHitService hits() {
    return hits;
  }

  public CrystalEffectEngine effects() {
    return effects;
  }

  public SubzoneService subzones() {
    return subzones;
  }

  public VisualService visual() {
    return visual;
  }

  public GuiService gui() {
    return gui;
  }

  public CityCommand commands() {
    return commands;
  }
}
