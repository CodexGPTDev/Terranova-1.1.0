# Dependencias

El JAR distribuye bibliotecas de código abierto. Los textos de licencia se conservan en `META-INF/licenses/` y en los metadatos incluidos por los artefactos Maven. La licencia MIT del código original no sustituye la licencia de cada dependencia. Las dependencias de compilación y prueba no se distribuyen como plugins completos.

| Dependencia | Versión | Uso | Proyecto |
|---|---|---|---|
| sqlite-jdbc | 3.51.1.0 | Incluida, persistencia SQLite | https://github.com/xerial/sqlite-jdbc |
| mysql-connector-j | 9.5.0 | Incluida, conector MySQL/MariaDB | https://github.com/mysql/mysql-connector-j |
| Adventure MiniMessage / legacy serializer | 4.25.0 | Incluidas; paquete relocalizado | https://github.com/KyoriPowered/adventure |
| Spigot API | 1.21.11-R0.1-SNAPSHOT | Solo compilación | https://hub.spigotmc.org/ |
| PlaceholderAPI | 2.11.7 | Solo compilación; plugin opcional | https://github.com/PlaceholderAPI/PlaceholderAPI |
| JUnit Jupiter | 5.13.4 | Solo pruebas | https://junit.org/ |
| Shadow Gradle plugin | 9.4.0 | Empaquetado | https://github.com/GradleUp/shadow |
| Gradle wrapper | 9.5.1 | Construcción | https://gradle.org/ |

Vault, ExcellentEconomy, AuraSkills y AdvancedEnchantments no están incluidos. Sus adaptadores usan los servicios/eventos de la instalación del servidor. El servidor Paper utilizado para comprobar el proyecto tampoco forma parte de esta distribución.

## Licencias incluidas en producción

| Biblioteca | Licencia / texto conservado |
|---|---|
| Terranova, código original | MIT, `Terranova-LICENSE.txt` |
| SQLite JDBC | Apache 2.0 y aviso BSD heredado, `sqlite-jdbc-LICENSE*.txt` |
| MySQL Connector/J | GPLv2 con Universal FOSS Exception 1.0 y permisos adicionales descritos en `mysql-connector-j-LICENSE.txt` |
| Protocol Buffers Java 4.31.1, transitiva de MySQL | BSD de tres cláusulas, `protobuf-license.txt` |
| Adventure API, key, MiniMessage y legacy serializer | MIT, `adventure-license.txt` |
| Examination API/string 1.3.0, transitiva de Adventure | MIT, `examination-license.txt` |

Fuentes de los textos: artefactos Maven de SQLite/MySQL y los repositorios oficiales [Adventure v4.25.0](https://github.com/KyoriPowered/adventure/tree/v4.25.0), [Examination v1.3.0](https://github.com/KyoriPowered/examination/tree/v1.3.0) y [Protocol Buffers v31.1](https://github.com/protocolbuffers/protobuf/tree/v31.1). Conserva estos avisos al redistribuir el JAR.
