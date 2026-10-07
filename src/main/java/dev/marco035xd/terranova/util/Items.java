package dev.marco035xd.terranova.util;

import dev.marco035xd.terranova.config.ConfigCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Identity and authentication are separate from the display name/lore. */
public final class Items {
  public record Identity(String kind, String id, UUID token) {}

  private final NamespacedKey kind, id, token, signature, receipt;
  private final byte[] secret;

  public Items(JavaPlugin p) throws IOException {
    kind = new NamespacedKey(p, "kind");
    id = new NamespacedKey(p, "definition");
    token = new NamespacedKey(p, "token");
    signature = new NamespacedKey(p, "signature");
    receipt = new NamespacedKey(p, "delivery");
    File f = new File(p.getDataFolder(), "item-secret.key");
    if (f.exists()) secret = java.nio.file.Files.readAllBytes(f.toPath());
    else {
      secret = new byte[32];
      new SecureRandom().nextBytes(secret);
      java.nio.file.Files.write(f.toPath(), secret);
    }
    if (secret.length != 32) throw new IOException("Invalid item-secret.key");
  }

  public ItemStack display(ConfigurationSection s, Material fallback, Map<String, ?> vars) {
    Material m =
        s == null ? fallback : ConfigCatalog.material(s.getString("material", fallback.name()));
    ItemStack item = new ItemStack(m);
    ItemMeta meta = item.getItemMeta();
    if (s != null) {
      meta.setDisplayName(Text.color(Text.replace(s.getString("name", m.name()), vars)));
      meta.setLore(
          s.getStringList("lore").stream().map(v -> Text.color(Text.replace(v, vars))).toList());
      if (s.contains("custom-model-data")) meta.setCustomModelData(s.getInt("custom-model-data"));
      meta.setEnchantmentGlintOverride(s.getBoolean("glow", false));
      if (meta instanceof SkullMeta skull) {
        String texture = s.getString("player-head-texture", "");
        if (!texture.isBlank())
          try {
            String json = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            var matcher =
                java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (matcher.find()) {
              var profile =
                  Bukkit.createPlayerProfile(
                      UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)));
              var textures = profile.getTextures();
              textures.setSkin(java.net.URI.create(matcher.group(1)).toURL());
              profile.setTextures(textures);
              skull.setOwnerProfile(profile);
            }
          } catch (Exception ignored) {
          }
      }
    }
    item.setItemMeta(meta);
    return item;
  }

  public ItemStack simple(Material m, String name, String... lore) {
    ItemStack i = new ItemStack(m);
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName(Text.color(name));
    meta.setLore(Arrays.stream(lore).map(Text::color).toList());
    i.setItemMeta(meta);
    return i;
  }

  public ItemStack signed(ItemStack item, String k, String definition, UUID t) {
    ItemStack copy = item.clone();
    copy.setAmount(1);
    ItemMeta m = copy.getItemMeta();
    var p = m.getPersistentDataContainer();
    p.set(kind, PersistentDataType.STRING, k);
    p.set(id, PersistentDataType.STRING, definition);
    p.set(token, PersistentDataType.STRING, t.toString());
    p.set(signature, PersistentDataType.BYTE_ARRAY, hmac(k + ":" + definition + ":" + t));
    copy.setItemMeta(m);
    return copy;
  }

  public Identity identity(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return null;
    var p = item.getItemMeta().getPersistentDataContainer();
    Identity current = identity(p, kind, id, token, signature);
    if (current != null) return current;
    // A renamed installation may keep authentic objects in players' inventories.
    // Discover their namespace rather than embedding another project's name.
    for (NamespacedKey key : p.getKeys()) {
      if (!key.getKey().equals("kind") || key.getNamespace().equals(kind.getNamespace())) continue;
      String ns = key.getNamespace();
      Identity previous =
          identity(
              p,
              key,
              new NamespacedKey(ns, "definition"),
              new NamespacedKey(ns, "token"),
              new NamespacedKey(ns, "signature"));
      if (previous != null) return previous;
    }
    return null;
  }

  private Identity identity(
      PersistentDataContainer p,
      NamespacedKey kindKey,
      NamespacedKey definitionKey,
      NamespacedKey tokenKey,
      NamespacedKey signatureKey) {
    String k = p.get(kindKey, PersistentDataType.STRING),
        i = p.get(definitionKey, PersistentDataType.STRING),
        t = p.get(tokenKey, PersistentDataType.STRING);
    byte[] sig = p.get(signatureKey, PersistentDataType.BYTE_ARRAY);
    if (k == null || i == null || t == null || sig == null) return null;
    try {
      UUID u = UUID.fromString(t);
      return MessageDigest.isEqual(sig, hmac(k + ":" + i + ":" + u)) ? new Identity(k, i, u) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private byte[] hmac(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret, "HmacSHA256"));
      return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public void receipt(ItemStack i, UUID value) {
    ItemMeta m = i.getItemMeta();
    m.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, value.toString());
    i.setItemMeta(m);
  }

  public UUID receipt(ItemStack i) {
    if (i == null || !i.hasItemMeta()) return null;
    var data = i.getItemMeta().getPersistentDataContainer();
    UUID current = receipt(data.get(receipt, PersistentDataType.STRING));
    if (current != null) return current;
    for (NamespacedKey key : data.getKeys())
      if (key.getKey().equals("delivery")) {
        UUID previous = receipt(data.get(key, PersistentDataType.STRING));
        if (previous != null) return previous;
      }
    return null;
  }

  private UUID receipt(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
