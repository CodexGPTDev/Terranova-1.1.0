# Terranova 1.1.0

Proyecto de código fuente libre bajo licencia **MIT**. Terranova añade ciudades a Minecraft mediante núcleos físicos, regiones, niveles YAML, miembros, permisos individuales, almacén, cristales modulares, parcelas 3D, mercado, escudos y eventos globales. La implementación es original y no incorpora código ni recursos de plugins comerciales.

## Novedades de 1.1.0

- Al eliminar una ciudad se devuelve el **núcleo de su tipo**, los cristales instalados y el contenido del almacén, en una transacción con las entregas pendientes.
- `/city give` informa del resultado y prepara las identidades junto con sus entregas en una sola transacción.
- `/city claim` reclama objetos pendientes. Si no hay espacio, se conservan hasta liberar un slot o volver a conectar.
- Nombre, paquetes Java, permisos, placeholders, mensajes y carpeta de datos usan Terranova.
- Los objetos autenticados de una instalación renombrada siguen funcionando al conservar su clave y base de datos.

## Instalación

1. Detén el servidor y copia `Terranova-1.1.0.jar` a `plugins/`.
2. Inicia el servidor. Se generará `plugins/Terranova/` con todos los ejemplos y una base SQLite.
3. Instala Vault y un proveedor de economía si vas a usar los precios predeterminados. ExcellentEconomy es opcional para monedas adicionales. Sin proveedor, las operaciones con costo monetario se rechazan sin conceder la compra.
4. Desde la consola: `city give TU_JUGADOR classic`. Coloca el núcleo y haz clic derecho.
5. Usa `/city help`. Para probar cristales: `city crystal give TU_JUGADOR guardian` y mejora la ciudad hasta el nivel requerido.

Ejemplo desde consola:

```text
city give TU_JUGADOR classic
city give TU_JUGADOR fortress
```

Dentro del juego los comandos llevan `/`. También puedes usar `/terranova` o `/tn` como alias de `/city`. Al eliminar una ciudad, el núcleo devuelto puede fundar otra al nivel inicial configurado de su tipo. La devolución pertenece al propietario, aunque un administrador ejecute la eliminación.

Para actualizar una instalación existente conserva sus datos siguiendo [MIGRACION.md](docs/MIGRACION.md).

Compilado contra la API Spigot 1.21.11, con bytecode Java 21; ejecutado y comprobado en Paper 26.2 build 132 con Java 25. Bukkit/Spigot/Purpur 1.21.11+ y Paper 26.1 son objetivos de compatibilidad de la API, pero no se ejecutaron en esta entrega. Usa la versión de Java exigida por tu distribución del servidor. No se ofrece compatibilidad Folia.

## Compilación

Necesitas JDK 25 y acceso a los repositorios Maven en la primera compilación. El wrapper incluye Gradle 9.5.1.

```bash
./gradlew build
# Windows:
gradlew.bat build
```

Instala `build/libs/Terranova-1.1.0.jar`. El archivo con sufijo `-thin.jar` no incluye las dependencias necesarias. El JAR de producción incorpora los conectores SQLite/MySQL y las bibliotecas de texto; los plugins de integración son dependencias opcionales independientes.

## Funciones incluidas

- Protección matemática en toda la altura del mundo, núcleo que no puede romperse mediante las acciones gestionadas, hologramas TextDisplay y bordes temporales con partículas.
- Niveles definidos por YAML sin máximo de cantidad codificado. Capacidad de miembros, almacenamiento paginado, slots, resistencia de raid y subzonas cambian con el nivel.
- Reglas con audiencia, reglas individuales por jugador y reglas independientes por subzona.
- Siete manejadores registrados: pociones, vuelo, bloqueo de objetos, AuraSkills, AdvancedEnchantments, resistencia de raid y crecimiento de cultivos.
- Activación `ALWAYS`, `RAID_ONLY`, `NON_RAID`; incompatibilidades comprobadas en ambos sentidos; instalación y retiro controlados.
- Selección 3D inclusiva por dos posiciones; volumen calculado sin recorrer bloques; creación, compra y venta de parcelas con propietario independiente.
- Escudos que excluyen las reglas, golpes y robo del raid; variantes RAID y BOOSTER en archivos independientes; prioridades y overrides temporales.
- BossBars, títulos, ActionBars, sonidos, HEX, formato legacy y MiniMessage.
- Contadores de golpes con cooldown por jugador/bloque, expiración y límites de memoria.
- SQLite o MySQL/MariaDB, migraciones SQL, revisión optimista de ciudad, registro de identidades de objetos y colas persistentes de entregas y pagos.
- API pública registrada como servicio Bukkit y expansión PlaceholderAPI.

## Archivos y guías

| Archivo | Contenido |
|---|---|
| `docs/COMANDOS.md` | Comandos, permisos y roles |
| `docs/CONFIGURACION.md` | YAML, costos, cristales, eventos y bases de datos |
| `docs/ARQUITECTURA.md` | Servicios, decisiones, persistencia y API |
| `docs/PRUEBAS.md` | Resultados comprobados y checklist de cliente |
| `docs/MIGRACION.md` | Actualizar la instalación y conservar objetos y ciudades |
| `docs/GITHUB.md` | Publicar el proyecto y sus releases |
| `CHANGELOG.md` | Historial de cambios |
| `docs/testing/` | Evidencia de compilación, JUnit y auditoría de servidor |
| `src/main/resources/` | Configuración y ejemplos que se distribuyen en el JAR |
| `src/test/` | Pruebas JUnit |
| `src/integrationTest/` | Complemento de auditoría para un servidor aislado |

## Operación y recuperación

Conserva juntos la base de datos y `item-secret.key`: esa clave autentica los núcleos y cristales existentes. Detén el servidor para hacer una copia consistente de la carpeta de datos; con MySQL respalda también la base externa. No distribuyas la carpeta de datos de tu servidor ni su clave junto con el proyecto.

`/city reload` recarga YAML y valida las definiciones utilizadas. No permite borrar tipos, niveles o cristales en uso, cambiar radios activos ni reducir capacidades ocupadas. Para cambiar radios, añade un nivel nuevo y usa una mejora. Los cambios de motor, credenciales y parámetros de tareas requieren reiniciar.

Las entregas pendientes esperan al jugador y a un espacio libre. Los objetos firmados tienen identidad única y su ubicación se mueve en la misma transacción SQL que la ciudad. El almacenamiento y la entrega del inventario de Minecraft no comparten transacción con SQL: un cierre normal drena la cola SQL, pero un corte abrupto entre la escritura del jugador y SQL puede requerir conciliación de objetos ordinarios. No se afirma una garantía de exactamente una entrega frente a cualquier pérdida de energía.

Los cobros mixtos verifican cada respuesta del proveedor y compensan los pasos anteriores si un paso falla. La venta de parcela registra la transferencia al vendedor en una cola persistente dentro de la transacción de propiedad. Un pago externo incierto queda en `REVIEW`, evitando repetirlo a ciegas. Consulta `/city admin finance list`, verifica el historial del proveedor y usa `retry` si no se pagó, o `ack` si ya se pagó. Una economía externa y la base de este plugin tampoco forman una única transacción distribuida.

## Integraciones y verificación

Las pruebas JUnit y la auditoría de Paper comprueban geometría, persistencia, revisiones, rollback, colas y los principales flujos de ciudad, incluyendo la entrega y devolución de ambos núcleos. La auditoría usa un mundo y entidades reales con jugadores simulados; no sustituye una sesión visual con el cliente Minecraft. Véase `docs/PRUEBAS.md` para resultados y alcance exacto.

Vault, ExcellentEconomy, AuraSkills, AdvancedEnchantments y PlaceholderAPI están implementados mediante adaptadores opcionales. Se contrastaron los contratos disponibles; no se ejecutó un servidor con esos cinco plugins instalados ni una base MySQL/MariaDB externa. Verifica esos escenarios con las versiones de tu servidor antes de habilitar la economía o raids para jugadores. La ausencia de todos esos plugins sí fue comprobada durante el arranque de Paper.

Fuentes oficiales consultadas: [Paper](https://docs.papermc.io/paper/dev/project-setup/), [VaultAPI](https://github.com/MilkBowl/VaultAPI), [ExcellentEconomy API](https://nightexpressdev.com/excellenteconomy/utility/developer-api/), [AuraSkills API](https://wiki.aurelium.dev/auraskills/api), [AdvancedEnchantments API](https://ae.advancedplugins.net/for-developers/plugin-api).

## Licencia

El código original está bajo MIT; consulta `LICENSE`. Las dependencias conservan sus licencias respectivas; consulta `THIRD_PARTY.md` y sus metadatos dentro del JAR. No se incluyen plugins comerciales, vídeos de referencia, servidor Paper ni herramientas de desarrollo en el proyecto entregado.

## Publicación y contribuciones

Descomprime el proyecto, sube su contenido a la raíz de tu repositorio y usa este README como portada. [GITHUB.md](docs/GITHUB.md) incluye los pasos para crear una release descargable. El workflow incluido compila y ejecuta los tests en GitHub Actions; no publica automáticamente una release.

Consulta [CONTRIBUTING.md](CONTRIBUTING.md) para preparar cambios. Los reportes de errores deben incluir versión del servidor, Java, Terranova, pasos para reproducir y el fragmento correspondiente del log; evita compartir contraseñas o la clave de objetos.
