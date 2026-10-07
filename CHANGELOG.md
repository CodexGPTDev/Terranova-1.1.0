# Historial

## 1.1.0

- Nombre público y proyecto: Terranova, autor Marco035xd, código original MIT.
- Paquetes Java `dev.marco035xd.terranova`, permisos `terranova.*`, expansión PlaceholderAPI `terranova`, carpeta `plugins/Terranova/`.
- Eliminación: devolución atómica del núcleo del tipo original, cristales y almacén al propietario.
- Eliminación desde otra zona: retirada del bloque físico aunque su chunk no esté cargado.
- `/city give`: identidades y entregas en una transacción; confirmación del resultado.
- `/city claim`: consulta y entrega de objetos pendientes con feedback de inventario lleno.
- Entregas: comprobación de la conexión activa y conservación de pendientes al desconectar.
- Lectura de objetos autenticados y recibos de namespaces anteriores conservando la clave.
- Tests para rollback de entregas, reutilización de núcleo, inventario lleno y reinicio.
- Documentación de configuración, comandos, API, actualización y GitHub; workflow de compilación.

## 1.0.0

- Implementación inicial de ciudades, niveles, regiones, miembros, reglas, almacén, cristales, parcelas, mercado, escudos y eventos.
