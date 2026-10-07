# Contribuir a Terranova

Necesitas JDK 25. Ejecuta `./gradlew build` (o `gradlew.bat build` en Windows) y comprueba los resultados JUnit. Los cambios de inventarios, identidades o persistencia deben cubrir pérdida/duplicación, errores de transacción y reconexión.

Para probar en Paper, utiliza una carpeta de servidor aislada. `./gradlew integrationJar` genera el complemento de auditoría; las instrucciones y limitaciones están en `docs/PRUEBAS.md`. Este complemento modifica bloques y datos de prueba.

Mantén los eventos Bukkit y las mutaciones de inventario en el hilo principal; JDBC usa su ejecutor. Centraliza decisiones en los servicios y registra nuevos módulos de cristal sin depender de ID concretos. Documenta cambios de schema, permisos y YAML.

Las contribuciones al código original se publican bajo MIT. Conserva las licencias de las dependencias y no añadas recursos comerciales sin autorización. En una pull request explica el problema, el comportamiento resultante y las pruebas ejecutadas.
