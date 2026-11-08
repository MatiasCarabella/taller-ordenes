# Verificación de H7: canales y avisos

Fecha: 8 de noviembre de 2026.

Estado: implementación y validación local completadas. Falta configuración y recepción con proveedores reales para cerrar H7.

## Implementación

- Gestión de canales propia de Cliente en Mi cuenta.
- Cambio de correo único y dirección de acceso, con revocación de confirmación y solicitudes pendientes.
- Código aleatorio de correo y enlace Telegram: hash SHA-256, finalidad, diez minutos y consumo transaccional de un uso. Un token nuevo reemplaza al anterior de cualquier canal.
- Chat privado, emisor humano coincidente, cuenta activa y unicidad por chat. La carrera perdedora no consume su token.
- Verificación de identidad de bot y ausencia de webhook antes de emitir enlaces; long polling en el backend, offset en memoria.
- Avisos R7 después del commit, resultados por canal y negocio conservado ante fallos. Sin historial, cola persistente ni reenvío automático.
- SMTP con TLS obligatorio y HTTPS en Telegram, timeout y concurrencia acotados.
- Configuración protegida en archivo de secretos, fuera del bundle y Git.
- Vista sin hashes, chat ID o códigos; código por correo no devuelto al navegador. No hay refresco periódico del portal que mantenga artificialmente la sesión.

Se conservan las cinco entidades y las dos migraciones existentes.

## Pruebas automáticas

Suite completa en Docker: 131 pruebas, 0 fallos, 0 errores y 0 omitidas. Se conservaron las 94 anteriores. Treinta nuevas pruebas HTTP/MySQL cubren:

- Perfiles, sesión, CSRF y propiedad, sin asignación directa de chat desde la API.
- Hash, aleatoriedad, envío del código después del commit y ausencia del código en la respuesta.
- Uso único, vencimiento exacto, finalidad y reemplazo entre canales.
- Cambio de correo, unicidad, conservación de contraseña, nueva dirección de login y estado de sesión actualizado.
- Límite entre códigos y proveedores no configurados sin invalidación de un token existente.
- Resultado incierto de SMTP sin confirmar falsamente el correo.
- Chat privado, bot/emisor inválido, cuenta inactiva, vínculo único y dos vinculaciones concurrentes.
- Dos confirmaciones concurrentes y revocación al desvincular.
- Offset de polling, grupos/otros comandos ignorados y ausencia de polling si falla la comprobación.
- Avisos con cero, uno o dos canales confirmados; solo se usa cada canal confirmado.
- Contenido mínimo sin valores internos, envío después del commit observado desde conexión independiente.
- Falla de uno/ambos proveedores sin rollback, diferenciación de proveedor no habilitado, acciones sin evento y operaciones revertidas sin aviso.
- Eventos R7, entrega repetida sin reenvío y espera acotada sin deshacer el negocio.
- Enlace denegado mientras no se verifica identidad, conexión y ausencia de webhook.

Siete pruebas de adaptadores contra servidores locales verifican POST y JSON de Telegram, identidad/webhook, ausencia de borrado automático de webhook, offset/timeout, emisor privado, texto sin parse_mode, vista previa de enlaces deshabilitada, errores sin token/cause crudos, SMTP sin degradación a texto plano ni credenciales antes de TLS, y URL remota de portal HTTPS.

Registros locales verification-h7.log, excluidos de Git. Los dobles de transporte y servidores locales están solo en pruebas; no existe un modo de envío simulado en la instancia del usuario.

## Navegador

Chrome sobre taller-ordenes-h7-qa, puerto 8087, con base y volumen propios. Su archivo de canales es separado y deshabilita proveedores externos; no monta las claves de canales del usuario.

Se probó contra la API real el cambio de correo y actualización de los datos de acceso, perfiles, publicación de presupuestos y resultados SIN_CANAL/NO_CONFIGURADO. El aviso de resultado permanece visible después de refrescar la orden.

La interacción de confirmación, enlace y desvinculación se probó con respuestas interceptadas explícitamente en ese navegador: código inválido/correcto (incluidos guion y guion bajo), enlace temporal, actualización manual y confirmación de desvinculación. La lógica de esos flujos se comprobó además contra MySQL real en las pruebas HTTP.

Vista de escritorio y 390×844, sin desborde ni errores JavaScript. Una sesión invalidada en el servidor llevó al login al actualizar canales. Se retiraron únicamente contenedores, red y volumen del proyecto temporal.

## Conectividad real comprobada

El 8 de noviembre de 2026 el usuario cargó la configuración mediante el asistente y aplicó los servicios. Los tres contenedores permanecen saludables. Un diagnóstico con los adaptadores de la aplicación confirmó autenticación y conexión SMTP bajo TLS, e identidad del bot de Telegram con ausencia de webhook (CONECTADO).

El diagnóstico solo abrió conexiones y consultó getMe/getWebhookInfo: no envió mensajes ni consumió actualizaciones del bot. No se imprimieron ni registraron credenciales, direcciones, chats o tokens. Esta comprobación valida configuración y conectividad; la recepción del código y de avisos todavía requiere el recorrido de la cuenta Cliente.

## Prueba real pendiente

- [x] Configuración local SMTP y bot propio, sin compartir claves.
- [ ] Recepción del código y confirmación de correo en una cuenta de prueba autorizada.
- [ ] Recepción /start y confirmación de vínculo en Telegram privado.
- [ ] Publicación de presupuesto con recepción efectiva de ambos avisos, además del resultado al actor.

Guía: [CONFIGURAR_CANALES.md](CONFIGURAR_CANALES.md). H7 no se marca completo hasta comprobar estos puntos.

## Despliegue local y punto de recuperación

Implementación instalada con docker compose up -d --wait. Backend, frontend y MySQL saludables, /api/health UP e interfaz de canales/avisos servida por el proxy. Se conservaron el contenedor MySQL original, taller-ordenes_mysql_data y las credenciales de las cuentas. No se ejecutó precarga ni reposición de claves en la instancia del usuario. Se comprobó la retirada del entorno temporal de navegador.

Punto de recuperación de la implementación: etiqueta local h7-canales-base. El plan conserva H7 abierto hasta recepción real; ambas copias del plan coinciden.
