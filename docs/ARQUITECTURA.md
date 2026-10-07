# Arquitectura

Las coordenadas y el estado de ciudad son propios; no se requiere un plugin de claims. El proyecto organiza la lógica en servicios y deja a los listeners traducir eventos Bukkit.

| Paquete | Responsabilidad |
|---|---|
| `model` | City, Subzone, Cuboid, Audience, FlagRule y SpatialIndex |
| `config` | Catálogo validado de YAML y niveles ordenados |
| `service` | Ciudades, subzonas, eventos, decisiones, golpes, visuales y entregas |
| `crystal` | Registro de manejadores y composición de EffectPlan |
| `storage` | Codec de snapshots, repositorio JDBC y migraciones |
| `gui` | Inventarios virtuales, paginación, formularios y confirmaciones |
| `listener` | Protección de bloques, entidades, movimiento, objetos y entorno |
| `hook` | Adaptadores opcionales de economía, skills, encantamientos y placeholders |
| `api` | Servicio público de consulta/extensión y CityChangeEvent |

## Decisiones

`ProtectionDecisionService` devuelve `Decision(allowed, reason, city)` y proporciona métodos para romper, colocar, interactuar, dañar, utilizar, entrar y teletransportar. Orden de resolución:

1. Bypass explícito; mundo habilitado; búsqueda de ciudad.
2. Selección de subzona y propietario/miembros correspondientes.
3. Base de acceso por rol; regla individual si existe.
4. Flag de subzona o ciudad y audiencia; regla maestra `build` para construcción.
5. Override temporal de evento de mayor prioridad. EventService excluye RAID si existe escudo.
6. Denegación de objetos por cristal, que conserva precedencia sobre un raid permisivo.
7. Guardas externas para acciones de jugadores permitidas por la política interna.

El núcleo se protege también frente a explosiones, pistones y cambios de entidades. Las restricciones whitelist/blacklist y golpes se consultan en el servicio de raid. Las fronteras de ciudad y subzona se consideran en el traslado por pistón/hopper. Otro plugin que reescriba directamente bloques o inventarios sin emitir eventos Bukkit deberá consultar esta API para cooperar.

## Estado y concurrencia

Cada City contiene UUID de núcleo/ciudad, propietario, mundo, posición, cuboide, nivel, nombre, tag, miembros, flags, reglas individuales, slots de almacén, cristales, subzonas, escudo y preferencias del holograma. Las fechas de escudo y eventos son absolutas.

Las mutaciones de objetos Bukkit, GUI y snapshots se hacen en el hilo principal. JDBC usa un único ejecutor ordenado. Una operación reserva la ciudad, copia su snapshot y realiza una actualización con revisión esperada. La memoria se sustituye después del commit. Una revisión obsoleta o token en ubicación distinta provoca rollback de toda la transacción, incluidas entregas. La GUI no mantiene inventarios de datos como fuente autoritativa.

Los núcleos y cristales incorporan PDC con tipo, definición, UUID y firma HMAC. La clave aleatoria es privada del servidor. `cp_tokens` registra ITEM, CORE, CITY o DELETED y verifica cada traslado. Duplicar visualmente un objeto firmado no permite instalar dos veces la misma identidad.

La concesión de varios objetos guarda sus identidades y entregas en una única transacción. Al eliminar una ciudad, el núcleo vuelve de CORE a ITEM conservando su UUID; la eliminación, el retorno de cristales y el contenido del almacén se guardan junto con todas sus entregas. El núcleo reutilizado crea una ciudad en el primer nivel del tipo.

`cp_delivery` guarda objetos pendientes; cada entrega lleva un recibo PDC y se comprueba el inventario antes de reconocer la cola. Con inventario lleno o propietario desconectado, la cola permanece pendiente. `/city claim` solicita otro intento. Los mensajes nuevos se añaden a `messages.yml` si faltan, sin sustituir traducciones existentes.

`cp_money_delivery` guarda transferencias al vendedor y devoluciones monetarias. READY se reclama como PROCESSING antes del proveedor externo; al reiniciar, PROCESSING pasa a REVIEW. Esto permite conciliación de resultados inciertos. Véase README para los límites entre SQL, playerdata y sistemas de economía externos.

`cp_preferences` ofrece persistencia de preferencias por jugador; la preferencia visible del holograma se guarda además con cada ciudad. Las selecciones temporales, formularios de chat y progreso de golpe no se persisten: se limpian al desconectar/expirar. Los eventos activos conservan ID, inicio y vencimiento.

## Rendimiento

SpatialIndex indexa por mundo/chunk y comprueba Y en la geometría final. Regiones con más de `performance.max-index-chunks` usan una colección de regiones grandes para no generar millones de referencias. Cuboid calcula el volumen mediante multiplicación con control de desbordamiento.

Movimiento recalcula efectos al cambiar de ciudad/subzona. Las pociones se renuevan por leases y no por tick. BossBars se actualizan una vez por segundo; partículas de núcleo requieren jugadores cercanos y los bordes limitan distancia y cantidad. Los menús se refrescan cuando cambia la ciudad y periódicamente para fechas visibles. Los mapas de golpes expiran y tienen límites configurables.

## API pública

En un plugin propio añade el JAR como `compileOnly` y declara `depend: [Terranova]` o una dependencia opcional con comprobación de presencia. Recupera el servicio:

```java
TerranovaApi api = Bukkit.getServicesManager().load(TerranovaApi.class);
if (api != null) {
    var view = api.cityAt(player.getLocation());
    var decision = api.decision(player, location, "block-break");
    api.registerGuard((actor, at, action) -> miPolitica(actor, at, action));
}
```

CityView expone colecciones inmutables y no permite modificar el agregado vivo. Llama decisiones y operaciones relacionadas con Bukkit desde el hilo principal. `CityChangeEvent` informa el UUID después de guardar una mutación; vuelve a consultar la vista si necesitas datos actuales.

Para módulos adicionales implementa `CrystalEffectHandler` con `path()` y `contribute(...)`, y regístralo mediante `api.crystalRegistry().register(handler)`. El manejador recibe la sección YAML correspondiente y añade su contribución al plan. Evita mutar jugadores dentro de `contribute`, que puede llamarse para consultas.

`api.registerEventType("MI_TIPO", factory)` añade callbacks de inicio/finalización. Los RAID/BOOSTER incorporados usan las políticas centrales; las factories permiten efectos adicionales. Un evento persistido de un tipo externo espera a que su proveedor registre la factory; conserva el vencimiento original y no obliga a cargar clases ausentes.

No hay comandos o manejadores específicos para el ID `guardian`: todos los cristales se componen a partir de módulos de YAML. Los niveles, variantes de raid, escudos y tipos de núcleo tampoco requieren recompilar.
