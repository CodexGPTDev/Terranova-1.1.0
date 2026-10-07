# Actualizar a Terranova 1.1.0

## Conservar una instalación anterior

1. Detén el servidor con `stop` y realiza una copia de su carpeta de datos y, si corresponde, de la base MySQL/MariaDB.
2. Retira el JAR anterior de `plugins/`.
3. Renombra la carpeta de datos anterior a `plugins/Terranova/`. Si ya existe una carpeta nueva con ese nombre, conserva primero ambas copias; usa como Terranova la carpeta que contiene tus ciudades reales.
4. Conserva `cities.db` y su `item-secret.key` juntos. Para una base externa conserva también las credenciales/configuración que la señalan. Los nombres de tablas existentes se mantienen para utilizar el mismo esquema.
5. Instala `Terranova-1.1.0.jar` y arranca. Los tipos y cristales instalados deben seguir presentes en YAML.
6. Actualiza los permisos de tu gestor a `terranova.*` y los placeholders a `%terranova_...%`. Los comandos `/city` se conservan.
7. Usa los mensajes de esta versión o cambia tu prefijo a Terranova si mantienes una traducción personalizada. Los mensajes nuevos de entrega se incorporan cuando faltan.

La autenticación comprueba los objetos de namespaces anteriores mediante su firma y la clave conservada. No se basa en el nombre o lore. Los objetos nuevos se firman bajo el namespace Terranova. Con una clave diferente, los objetos anteriores no se aceptan; por eso no debes mezclar la base de una instalación con la clave de otra.

## Devolución al eliminar

Una eliminación correcta prepara para el propietario el núcleo del tipo correspondiente, todos los cristales instalados y los stacks del almacén. La ciudad y los traslados de identidad se guardan con esas entregas en la misma transacción. Si la operación falla, se conserva la ciudad y no se crean devoluciones parciales.

Los objetos entran en el inventario si hay espacio. Si está lleno o el propietario está desconectado, esperan en la base de datos y se intentan entregar al conectar/liberar espacio. `/city claim` solicita la entrega manualmente y muestra el estado.

Las ciudades eliminadas con una versión anterior ya no contienen un registro de propietario/tipo completo que permita reconstruir automáticamente su devolución. Para reponer un núcleo perdido, el administrador puede usar:

```text
city give Marco035xd classic
city give Marco035xd fortress
```

La ciudad creada con el núcleo devuelto inicia en el primer nivel definido para su tipo.

## Solución de problemas

| Caso | Acción |
|---|---|
| Se indica que hay objetos pendientes | Libera un slot normal y usa `/city claim` |
| El núcleo devuelto no se reconoce | Comprueba que conservaste la clave junto con la base; revisa el tipo YAML |
| No tienes permisos tras actualizar | Cambia los nodos del gestor a `terranova.command.*` y `terranova.create` |
| El servidor detecta comandos duplicados | Comprueba que solo está cargado el JAR nuevo |
| Operación no guardada | Revisa el error SQL en consola y la conexión/archivo de base |
| Falla el arranque por tipo o nivel inexistente | Restaura la definición YAML utilizada por las ciudades |
