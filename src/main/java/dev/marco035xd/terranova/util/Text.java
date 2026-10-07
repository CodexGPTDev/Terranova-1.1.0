package dev.marco035xd.terranova.util;

import java.util.*;
import java.util.regex.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Text {
  private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
  private static final Pattern DURATION =
      Pattern.compile("([0-9]+)(ms|[smhd])", Pattern.CASE_INSENSITIVE);
  private static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();

  private Text() {}

  public static String color(String text) {
    if (text == null) return "";
    if (text.contains("<") && text.contains(">"))
      text = LEGACY.serialize(MiniMessage.miniMessage().deserialize(text));
    Matcher m = HEX.matcher(text);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      String h = m.group(1);
      StringBuilder c = new StringBuilder("§x");
      for (char v : h.toCharArray()) c.append('§').append(v);
      m.appendReplacement(b, Matcher.quoteReplacement(c.toString()));
    }
    m.appendTail(b);
    return ChatColor.translateAlternateColorCodes('&', b.toString());
  }

  public static String replace(String s, Map<String, ?> vars) {
    for (var e : vars.entrySet())
      s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
    return s;
  }

  public static void send(CommandSender p, String s) {
    p.sendMessage(color(s));
  }

  public static void action(Player p, String s) {
    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(s)));
  }

  public static long duration(String s) {
    if (s == null || s.isBlank()) throw new IllegalArgumentException("Duración inválida");
    Matcher m = DURATION.matcher(s);
    long n = 0;
    int end = 0;
    while (m.find()) {
      if (m.start() != end) throw new IllegalArgumentException("Duración inválida");
      long v = Long.parseLong(m.group(1));
      long mult =
          switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "ms" -> 1;
            case "s" -> 1000;
            case "m" -> 60000;
            case "h" -> 3600000;
            default -> 86400000;
          };
      n = Math.addExact(n, Math.multiplyExact(v, mult));
      end = m.end();
    }
    if (end != s.length() || n <= 0) throw new IllegalArgumentException("Usa 30s, 30m, 2h o 1d");
    return n;
  }

  public static String remaining(long ms) {
    long n = Math.max(0, ms / 1000);
    return String.format(Locale.ROOT, "%02d:%02d:%02d", n / 3600, n / 60 % 60, n % 60);
  }

  public static double money(double n) {
    if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Importe inválido");
    return n;
  }

  public static void sound(Player p, String name) {
    if (name == null || name.isBlank()) return;
    try {
      p.playSound(p.getLocation(), Sound.valueOf(name), .6f, 1f);
    } catch (IllegalArgumentException ignored) {
    }
  }
}
