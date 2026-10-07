# Pruebas de entrega

## Resultado comprobado

- Compilación Gradle: `BUILD SUCCESSFUL`.
- JUnit: 20 tests, 0 fallos, 0 errores; 6 de geometría/índice, 6 de cálculos/configuración y 8 de repositorio/transacciones.
- Paper 26.2 build 132, Java 25: 31 comprobaciones funcionales y 6 después de detener y reiniciar el servidor.
- Ausencia de Vault, ExcellentEconomy, PlaceholderAPI, AuraSkills y AdvancedEnchantments: el plugin inicia correctamente.
- JAR de entrega: compilado desde este proyecto; CRC del ZIP correcto, recursos YAML/SQL coincidentes y SHA-256 del JAR comprobado en el reinicio.

La auditoría ejecuta un servidor Paper real, bloques, TextDisplay, inventarios Bukkit, eventos y SQLite. Los jugadores son simulados mediante un complemento de prueba; no se conectó un cliente Minecraft gráfico. Las mejoras de esa auditoría se hicieron sin cobro administrativo, por lo que no prueban una retirada real de dinero por Vault/ExcellentEconomy.

## Evidencia

En `docs/testing/` se incluyen el registro de compilación, los XML de JUnit, los resultados iniciales y de reinicio, y extractos de los registros Paper. El complemento `TerranovaAudit` está en `src/integrationTest/` y se excluye del JAR de producción.

Los primeros 20 casos iniciales comprueban: núcleo/región, hologramas reales, menú/slots bloqueados, mejora de capacidades, instalación atómica de Guardian, audiencia de sus dos pociones, retirada al salir, cancelación de doble clic/shift/arrastre inválido, nivel 3, inactividad RAID_ONLY, BossBar y flags de raid, cooldown de golpes, blacklist de bedrock, escudo y robo, retorno de raid al quitar escudo, robo controlado/cola, restauración exacta de flags, volumen de subzona, rechazo de salida/solapamiento, snapshots de miembros/flags/inventario/escudo y vistas inmutables de API.

Las 11 comprobaciones nuevas ejecutan concesión, creación, preparación del contenido y eliminación de classic/fortress; verifican una devolución del núcleo con su identidad original, el cristal y los cinco diamantes del almacén. También comprueban que un inventario lleno conserva la devolución, la reutilización del núcleo, y que un objeto de namespace anterior mantiene su firma válida mientras una firma alterada se rechaza.

El reinicio comprueba nivel y límites, cristales e inventario, miembros/reglas/subzonas/escudo/preferencia de holograma, ausencia de modificación permanente por el raid terminado, conservación de la devolución pendiente y entrega mediante claim una sola vez.

Prueba adicional de actualización de mensajes: antes del reinicio se quitaron `delivery-pending` e `items-issued` y se personalizó `prefix`. Al arrancar se recuperaron las dos claves faltantes y se conservó el prefijo personalizado.

## Reproducir la auditoría

```bash
./gradlew build integrationJar
```

En un servidor Paper aislado y vacío copia `Terranova-1.1.0.jar` y `TerranovaAudit-1.1.0-test-only.jar` desde `build/libs/` a `plugins/`. Inicia con Java apropiado para Paper. El complemento de prueba crea datos deterministas entre X/Z=100 y 600, Y=70; espera `AUDIT COMPLETE 31 passed`. Ejecuta `stop`, reinicia y espera `AUDIT COMPLETE 6 passed`. Los resultados quedan en `plugins/TerranovaAudit/`.

No instales el complemento de auditoría en un servidor de jugadores: modifica bloques y crea ciudades de prueba. Para repetir desde cero usa otra carpeta de servidor o elimina exclusivamente los datos del servidor de prueba con el proceso detenido.

## Checklist del cliente solicitado en los requisitos

Esta tabla diferencia comprobación automática del paso visual o integración que debe confirmarse con un cliente y la instalación objetivo.

| Test | Comprobación automatizada | Comprobación adicional de cliente/integración |
|---|---|---|
| 1. Colocar núcleo | Núcleo físico, región y TextDisplay creados | Colocación por jugador y apariencia de partículas/holograma |
| 2. Abrir núcleo | Inventario y botones esperados | Clic derecho y legibilidad de la GUI |
| 3. Mejorar nivel | Capacidades y holograma actualizados | Cobro real mixto con proveedores instalados y feedback |
| 4. Slots | Estado bloqueado, libre e instalado | Diseño, paginación y arrastre válido del cliente |
| 5. Guardian | Resistance y Regeneration para miembros; visitante sin efectos | Iconos vanilla y apariencia del lore |
| 6. Salir | Se retiran efectos propios | Movimiento real y coexistencia con efectos de otros plugins |
| 7. Iniciar RAID | BossBar, overrides y RAID_ONLY | Presentación y golpes hasta rotura con herramientas reales |
| 8. Spam | No aumenta dos veces antes del cooldown | Herramientas rápidas y paquetes del cliente |
| 9. Robo | Cola y retiro controlados; escudo lo impide | Confirmación, inventario lleno, desconexión durante robo |
| 10. Terminar RAID | Flags base exactamente iguales | Comportamiento de jugador tras finalizar |
| 11. Subzona | Volumen y creación dentro de ciudad | Partículas 3D, ActionBar, precio y cobro real |
| 12. Fuera de ciudad | Rechazo de salida y solapamiento | Mensaje y selección mostrados al jugador |
| 13. Ender Pearl | Módulo para visitante activo durante raid | Lanzamiento y teletransporte real de perla/chorus |
| 14. Sin AE | Arranque correcto | Con AE instalado: cancelar TRENCH/TELEPATHY con su versión |
| 15. Sin AuraSkills | Arranque correcto | Con AuraSkills: XP por grupo, stacking y regeneración de maná |
| 16. Reinicio | Datos principales y preferencias restaurados | Inventarios reales de jugadores y eventos activos en su instalación |

También se deben ejecutar con la instalación objetivo: compra/venta con proveedor real, fondos insuficientes, pagos inciertos y conciliación; conexión MySQL/MariaDB y sus migraciones; expansión PlaceholderAPI; variantes de eventos simultáneos y prioridad; explosiones, pistones/hoppers y plugins que escriben bloques directamente. Estos sistemas tienen implementación, pero no se certifica su funcionamiento con combinaciones externas que no se ejecutaron aquí.

Los registros Paper originales contienen errores de red al consultar actualizaciones de Paper/Mojang y avisos del entorno de ejecución; no pertenecen a Terranova. Los extractos entregados mantienen el arranque y todos los resultados del complemento auditado.
