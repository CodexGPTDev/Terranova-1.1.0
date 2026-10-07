# Configuración

Todos los archivos editables están en `plugins/Terranova/`. Los ejemplos originales están en `src/main/resources/`. Los ID de archivo y de definición identifican registros persistentes: no renombres un tipo o cristal utilizado. Cada carpeta acepta archivos YAML adicionales; no hay una lista fija de ID en la lógica.

## Archivos

| Ruta | Función |
|---|---|
| `config.yml` | Mundos, límites, base de datos, rendimiento, selección, bordes, hologramas y feedback |
| `messages.yml` | Mensajes y ayuda en español |
| `gui.yml` | Títulos, botones principales, materiales y lore de slots |
| `flags.yml` | Reglas iniciales y representación de cada regla |
| `city-types/*.yml` | Núcleo, niveles, capacidades y costos |
| `crystals/*.yml` | Objetos, activación, incompatibilidades y módulos |
| `events/*.yml` | Variantes RAID/BOOSTER, overrides, BossBar y feedback |
| `shields/*.yml` | Duración, nivel mínimo y costo de escudos |

`enabled-worlds: []` habilita todos los mundos. Los límites por permisos se combinan tomando el mayor valor válido. Los rangos numéricos se validan: no se admiten precios negativos, infinitos o NaN. Existen límites de seguridad para radios, capacidades y multiplicadores, pero no un máximo de siete niveles ni de efectos por cristal.

Los elementos de presentación admiten `material`, `custom-model-data`, `player-head-texture`, `name`, `lore` y `glow`. `player-head-texture` usa el valor Base64 de una textura de cabeza. El formato acepta `&a`, `&#66E6B5` y MiniMessage. En nombres escritos por jugadores se impiden tags MiniMessage; `names.allow-colors` controla colores legacy.

## Niveles y costos

`levels` es un mapa ordenado de enteros positivos. La mejora elige la siguiente clave disponible, incluso si hay saltos. Cada nivel contiene `radius`, `max-members`, `chest-rows`, `crystal-slots`, `raid-block-hits`, `max-subzones`, `max-subzone-blocks`, `change-name`, `gui-display` y `upgrade-cost`. Radio R protege `(2R+1) × altura del mundo × (2R+1)` bloques; la subzona usa sus límites Y propios.

El lore de mejora admite `{current-radius}` / `{target-radius}` y sus pares para `members`, `storage-rows`, `crystal-slots`, `raid-hits`, `subzones`, `subzone-blocks`. `{target-level}`, `{level}` y los placeholders generales también están disponibles.

Ejemplo de costo mixto:

```yaml
upgrade-cost:
  vault: 1000
  excellent-economy:
    gems: 25
  items:
    - material: DIAMOND
      amount: 4
```

`vault` necesita un servicio Economy registrado en Vault. `excellent-economy` es un mapa de ID de moneda a cantidad. Los objetos firmados y objetos con metadatos especiales no se consumen como materiales ordinarios. Los requisitos repetidos del mismo material se suman antes de reservar. Para eliminar un costo, usa `upgrade-cost: {}`. No se simula dinero cuando falta proveedor.

El precio de creación de subzona es `volumen × subzones.price-per-block`, en `subzones.currency`. `vault` identifica el proveedor Vault; `ee:gems`, por ejemplo, identifica la moneda gems de ExcellentEconomy. Las ventas conservan su moneda y precio independientemente de otras parcelas.

## Cristales

Cada archivo define `id`, `enabled`, `item`, `required-city-level`, `activation-mode`, `incompatible-with` y los módulos necesarios. `raid-only: true` es alias de `RAID_ONLY`. El modo ALWAYS funciona dentro de la ciudad; RAID_ONLY requiere un raid efectivo para esa ciudad; NON_RAID requiere que no lo haya. Un escudo hace que la ciudad no tenga raid efectivo.

Cada módulo tiene audiencia `ALL`, `MEMBERS`, `NON_MEMBERS`, `OWNER` o `NON_OWNER`; propietario cuenta como miembro. Un módulo bajo `effects` puede heredar la audiencia de ese bloque. Los módulos no comparten obligatoriamente audiencia.

| Módulo | Propiedades |
|---|---|
| `effects.potion-effects` | `audience`, `list` de `{type, level, ambient, particles, icon}`; nivel 1 equivale a amplifier 0 |
| `effects.flight` | `audience`, `enabled` |
| `blocked-items` | `audience`, `materials`, incluidas ENDER_PEARL y CHORUS_FRUIT |
| `advanced-enchantments` | `audience`, `denied` con ID de encantamientos |
| `aura-skills` | `audience`, `stacking-mode`, `xp-multipliers`, `mana-regeneration.multiplier` |
| `effects.raid-resistance` | `audience`, `additional-hits` |
| `effects.crop-growth` | `audience`, `multiplier` |

El crecimiento actúa sobre la edad de cultivos y no cambia globalmente `randomTickSpeed`. Los efectos de poción se renuevan cada 60 ticks mediante leases de 160 ticks; se retiran al salir o cambiar las condiciones. Se respetan iconos vanilla y efectos externos más fuertes. El vuelo de supervivencia/adventure restaura el permiso anterior al terminar.

AuraSkills acepta varias filas `skills: [MINING, FARMING]` con su `multiplier`. En MULTIPLY los factores se multiplican, en HIGHEST se toma el mayor, en ADD se acumulan las bonificaciones sobre 1. Se limita el resultado finito para evitar desbordamientos. Si mezclas modos, cada operación se aplica sobre el acumulador en el orden de contribución de los cristales; usa el mismo modo entre ellos cuando quieras un resultado independiente de ese orden.

Ejemplos incluidos: `guardian`, `skybound`, `aura-scholar`, `raid-lockdown`, `bastion`, `harvest`. Un cristal puede combinar todos los módulos y cualquier cantidad de pociones válida para el servidor.

## Eventos y escudos

Duplica un YAML de `events/`, cambia `id` y sus propiedades, y recarga. Los tipos incorporados son RAID y BOOSTER; la API registra factories adicionales. Duraciones admiten unidades compuestas. No cambies el tipo de un evento mientras está activo: detenlo primero.

Para reglas en conflicto se elige la prioridad mayor; empates se ordenan por ID de forma estable. `forced-flags` nunca escribe en los flags permanentes. Los escudos excluyen todos los overrides RAID y sus golpes/robo; otros eventos BOOSTER pueden seguir funcionando.

BossBar admite colores Bukkit y overlays `PROGRESS`/`SOLID`, `SEGMENTED_6`, `SEGMENTED_10`, `SEGMENTED_12`, `SEGMENTED_20`; también alias `NOTCHED_6`, etc. Se actualiza una vez por segundo con `{remaining}`, `{event}` y `{duration}`.

`breaking.mode` es WHITELIST o BLACKLIST y `breaking.blocks` usa nombres de Material. `hit-cooldown-millis` controla cada pareja jugador/bloque; `block-hit-expiration` borra progreso inactivo. El contador de bloque se comparte entre atacantes, con cooldown individual. El núcleo permanece protegido. `drop-items` decide las entregas del bloque roto.

Robo: `crystals.stealable`, `steal-mode: GUI`, `confirmation`, `remove-after-steal`, `drop-to-attacker`. Solo se permite a visitantes con permiso de robo, durante el raid efectivo y sin escudo. Con `remove-after-steal: false` se mantiene el original y se concede una copia con identidad nueva, como recompensa explícita del evento; cada slot solo puede reclamarse una vez por ejecución del evento. Con `drop-to-attacker: false` no se entrega el objeto. La opción predeterminada retira el original y traslada su identidad al atacante.

Escudos: copia uno de `shields/*.yml`, cambia ID, duración, nivel mínimo y costo. El tiempo se guarda como fecha absoluta y no se reinicia al reiniciar el servidor.

## Bases de datos

SQLite es predeterminado y crea `cities.db`, utiliza WAL y `synchronous=FULL`. MySQL y MariaDB usan el mismo adaptador JDBC; configura:

```yaml
database:
  type: MYSQL # MARIADB también admitido
  mysql:
    host: localhost
    port: 3306
    database: terranova
    username: terranova
    password: 'TU_CONTRASEÑA'
    use-ssl: true
```

Crea antes la base y un usuario con permisos de crear/alterar tablas, leer, insertar, actualizar y borrar dentro de esa base. Las migraciones `db/migrations/V1__city_state.sql` y `V2__money_outbox.sql` se ejecutan por versión al iniciar; `cp_schema` registra la versión. Un esquema más nuevo que el plugin impide el arranque.

Cambiar `database.type` no copia automáticamente los datos de un motor a otro. Detén el servidor y realiza una migración verificada si quieres conservar las ciudades. No edites manualmente tokens ni revisiones mientras el plugin está activo.

## Integraciones

Vault se obtiene del ServicesManager y se comprueban respuestas de transacción. ExcellentEconomy usa su servicio API y resultado asíncrono `success()`, contrastado con 2.8.0. AuraSkills usa XpGainEvent y ManaRegenerateEvent, contrastados con API 2.3.3. AdvancedEnchantments se registra mediante su evento EffectsActivateEvent y se cancela por nombre de encantamiento; el contrato corresponde a la documentación oficial actual. Una API incompatible produce un aviso y no derriba el plugin. Cuando la dependencia no existe, el módulo se omite y solo se informa con `debug: true`.

PlaceholderAPI incluye, entre otros: `%terranova_has_city%`, `%terranova_owned_protections%`, `%terranova_city_name%`, `%terranova_city_level%`, `%terranova_city_radius%`, `%terranova_city_role%`, `%terranova_city_members%`, `%terranova_city_max_members%`, `%terranova_city_crystal_slots%`, `%terranova_city_shield_active%`, `%terranova_city_shield_remaining_seconds%`, `%terranova_city_raid_active%`, `%terranova_city_flag_pvp%`, `%terranova_event_raid_active%`, `%terranova_event_raid_remaining_seconds%`. Los flags mostrados son la configuración base; el estado efectivo se consulta con la API de decisiones.
