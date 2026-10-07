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
