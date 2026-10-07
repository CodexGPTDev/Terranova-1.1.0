package dev.marco035xd.terranova.hook;

import dev.marco035xd.terranova.TerranovaPlugin;
import java.util.function.Consumer;
import org.bukkit.event.*;
import org.bukkit.plugin.*;

/** Events are linked only when the soft dependency is present. */
public abstract class DynamicEventHook implements Listener {
  protected final TerranovaPlugin p;
  protected final String plugin;

  protected DynamicEventHook(TerranovaPlugin p, String name) {
    this.p = p;
    plugin = name;
  }

  @SuppressWarnings("unchecked")
  protected void listen(String name, Consumer<Event> consumer) {
    Plugin dependency = p.getServer().getPluginManager().getPlugin(plugin);
    if (dependency == null) {
      if (p.catalog().settings.getBoolean("debug"))
        p.getLogger().info(plugin + " ausente; integración desactivada");
      return;
    }
    try {
      Class<? extends Event> cls =
          (Class<? extends Event>)
              Class.forName(name, true, dependency.getClass().getClassLoader());
      p.getServer()
          .getPluginManager()
          .registerEvent(
              cls,
              this,
              EventPriority.HIGHEST,
              (l, e) -> {
                try {
                  consumer.accept(e);
                } catch (RuntimeException error) {
                  p.failure(error);
                }
              },
              p,
              true);
    } catch (ClassNotFoundException e) {
      p.getLogger().warning(plugin + ": API incompatible; evento " + name + " no disponible");
    }
  }

  protected Object call(Object target, String method, Object... args) {
    try {
      for (var m : target.getClass().getMethods())
        if (m.getName().equals(method) && m.getParameterCount() == args.length)
          return m.invoke(target, args);
      throw new NoSuchMethodException(method);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(plugin + " API: " + method, e);
    }
  }
}
