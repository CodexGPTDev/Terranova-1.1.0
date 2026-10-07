# Comandos y permisos

Comando raíz `/city`; alias `/cities`, `/terranova` y `/tn`. Los comandos de ciudad usan la ciudad situada en la posición del jugador o, si está fuera, una ciudad propia/de la que es miembro. Los nombres de jugadores desconectados deben corresponder a alguien que haya entrado antes; también se aceptan UUID.

| Comando | Función / rol |
|---|---|
| `/city help` | Ayuda |
| `/city claim` | Recibir objetos pendientes; informa si falta espacio o no hay entregas |
| `/city menu` | Menú del núcleo |
| `/city list` | Ciudades del jugador |
| `/city border` | Partículas temporales del borde |
| `/city hologram [on\|off\|toggle]` | Propietario: mostrar u ocultar holograma |
| `/city rename <texto>` | Propietario: nombre; depende del nivel; hasta 40 caracteres |
| `/city tag <texto>` | Propietario: etiqueta; hasta 12 caracteres |
| `/city add <jugador>` | Propietario: añadir miembro |
| `/city remove <jugador>` | Propietario: retirar miembro y sus reglas individuales |
| `/city flags` | Menú de reglas |
| `/city flag <flag> allow\|deny [audiencia]` | Propietario: regla de ciudad |
| `/city userflags <jugador>` | Menú de reglas individuales |
| `/city userflag <jugador> <flag> allow\|deny\|inherit` | Propietario: regla individual |
| `/city upgrade` | Mejorar al siguiente nivel YAML y pagar sus requisitos |
| `/city crystals` | Slots, instalación y retiro; visitantes autorizados por raid pueden reclamar mediante el flujo controlado |
| `/city storage` | Almacén comunitario según `chest-access` |
| `/city shield` | Comprar escudo con confirmación |
| `/city delete` | Propietario: eliminar con confirmación y devolver núcleo, cristales y almacén al propietario |
| `/city subzone wand` | Propietario de ciudad: herramienta de selección |
| `/city subzone cancel` | Cancelar selección |
| `/city subzone create <nombre>` | Crear cuboide seleccionado y cobrar por volumen |
| `/city subzone list\|menu\|view [subzona]` | Lista o menú de parcela |
| `/city subzone delete [subzona]` | Propietario de parcela o administrador: eliminar con confirmación |
| `/city subzone sell <subzona> [precio] [moneda]` | Poner en venta; sin precio abre el formulario y selector de moneda |
| `/city subzone unsell [subzona]` | Retirar de venta |
| `/city subzone buy [subzona]` | Comprar con confirmación |
| `/city subzone flags [subzona]` | Reglas de parcela |
| `/city subzone add\|remove <subzona> <jugador>` | Miembros propios de parcela |
| `/city subzone userflag <subzona> <jugador> <flag> allow\|deny` | Regla individual de parcela |

En selección, clic izquierdo marca posición 1 y clic derecho posición 2. La ActionBar muestra volumen y precio. El menú de subzonas informa si falta selección, nivel, capacidad o si la selección sale de la ciudad/se superpone. `subzones.city-owner-access` decide el acceso del propietario principal a las parcelas vendidas.

| Administración | Uso |
|---|---|
| `/city give <jugador> <tipo> [cantidad]` | Entregar núcleos firmados; jugador conectado; 1–64; cola persistente si falta espacio |
| `/city crystal give <jugador> <cristal> [cantidad]` | Entregar cristales firmados; 1–64 |
| `/city event start <id> [duración]` | Iniciar variante YAML; duración predeterminada si se omite |
| `/city event stop <id>` | Finalizar evento |
| `/city reload` | Recargar configuración validada |
| `/city admin list` | UUID y nombres de ciudades; funciona desde consola |
| `/city admin menu <uuid>` | Abrir una ciudad específica |
| `/city admin owner <uuid> <jugador>` | Cambiar propietario |
| `/city admin upgrade <uuid>` | Siguiente nivel, sin cobrar |
| `/city admin delete <uuid>` | Eliminar con confirmación |
| `/city admin finance list` | Pagos pendientes y su estado; consola admitida |
| `/city admin finance retry <uuid-pago>` | Reintentar un pago después de verificar que no llegó al proveedor |
| `/city admin finance ack <uuid-pago>` | Reconocer un pago ya realizado y quitarlo de la cola |

Los comandos que abren menús requieren jugador. `give`, `crystal give`, `event`, `reload`, `admin list` y `admin finance` admiten consola. Ejemplo de evento: `/city event start raid 30m`. Duraciones: `500ms`, `30s`, `1h30m`, `2d`.

## Permisos

Cada comando tiene `terranova.command.<subcomando>`; las acciones anidadas añaden otro segmento, por ejemplo `terranova.command.subzone.create` y `terranova.command.event.start`. Consulta la lista exacta en `src/main/resources/plugin.yml`. Los comandos normales tienen `default: true`; los administrativos `default: op`. Tener un permiso de comando no concede propiedad de una ciudad.

| Permiso | Efecto |
|---|---|
| `terranova.create` | Crear ciudad al colocar núcleo |
| `terranova.raid.steal` | Participar en el robo si el evento también lo permite |
| `terranova.admin` | Gestión administrativa y acceso a comandos |
| `terranova.bypass` | Saltarse decisiones ordinarias; valor predeterminado false, incluso para OP |
| `terranova.protections.limit.<n>` | Aumentar límite global de ciudades propias |
| `terranova.protections.limit.unlimited` | Sin límite global |
| `terranova.protections.limit.world.<mundo>.<n>` | Aumentar límite en ese mundo; nombre en minúsculas |
| `terranova.protections.limit.world.<mundo>.unlimited` | Sin límite para ese mundo |
| `terranova.protections.limit.world.unlimited` | Sin límite por mundo |

El núcleo no se destruye por romper bloques, incluso con bypass; se elimina mediante gestión de ciudad. El permiso administrativo permite administrar, pero no activa automáticamente el bypass de protección.

## Inventarios

Los menús son inventarios virtuales. Los slots decorativos nunca almacenan objetos del jugador. Instalación: cristal firmado de cantidad uno en cursor, clic o arrastre a un slot AVAILABLE. Los slots LOCKED/ocupados rechazan instalación. Retiro y robo usan acciones validadas y confirmaciones configurables.

El almacén muestra 45 slots por página, por lo que admite capacidades superiores a seis filas. Deposita un stack desde el cursor en un slot libre; pulsa un slot ocupado para retirar mediante la cola de entregas. Los metadatos y la identidad de los objetos firmados se conservan. Shift-click, doble clic, teclas numéricas, intercambio con mano secundaria y arrastres arbitrarios se cancelan en el menú.
