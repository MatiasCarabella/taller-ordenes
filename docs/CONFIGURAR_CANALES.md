# Configurar correo y Telegram

Fecha: 8 de noviembre de 2026.

El proyecto conserva frontend, backend y MySQL. SMTP y Telegram son proveedores externos; el bot se ejecuta por long polling dentro del backend. La aplicación funciona aunque estén sin configurar.

## Preparar proveedores

### Correo SMTP con Brevo

1. Crear la cuenta en Brevo y completar la verificación que solicite el servicio.
2. Agregar y verificar el correo remitente en Senders. Si el proveedor solicita autenticar un dominio, completar ese paso en su panel.
3. En SMTP & API, copiar servidor, puerto y Login SMTP; crear una clave SMTP. La clave SMTP es distinta de una API key y de la contraseña de acceso al panel.
4. Para el escenario previsto: smtp-relay.brevo.com, puerto 587 y STARTTLS. Usar el Login que muestra el panel, que puede diferir del correo remitente.

Referencias oficiales: [remitente](https://help.brevo.com/hc/en-us/articles/208836149-Create-a-new-sender-From-name-and-From-email), [SMTP](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP) y [clave SMTP](https://help.brevo.com/hc/en-us/articles/7959631848850-Create-and-manage-your-SMTP-keys).

### Telegram

1. Abrir el bot oficial [BotFather](https://t.me/BotFather) y enviar /newbot.
2. Elegir nombre y usuario disponible. Conservar el usuario (sin @) y el token.
3. El usuario debe corresponder al token. La aplicación comprueba esa identidad y que no haya webhook antes de ofrecer enlaces.
4. No pegar el token en el chat, archivos versionados ni comandos con argumentos.

Referencia: [creación de bot](https://core.telegram.org/bots/tutorial#obtain-your-bot-token).

## Guardar localmente

En PowerShell, desde la raíz del repositorio:

```powershell
./scripts/configurar-canales.ps1
```

Solicita URL del portal, SMTP y bot; lee claves/token sin mostrarlos. Guarda .secrets/canales.properties, con permisos restringidos y excluido de Git. No reemplaza las contraseñas de BDD ni las de las cuentas de la aplicación.

El remitente SMTP es quien envía. El destinatario de códigos/avisos es el correo de la cuenta Cliente, que se configura desde Mi cuenta.

La URL del portal usa HTTPS en remoto. Para pruebas en esta PC se admite http://localhost:8080; un enlace localhost abierto en un celular apunta al celular. El enlace de vinculación t.me sí puede abrirse en Telegram móvil; la consulta local del portal se hace en la PC hasta el despliegue de H8.

Si se modifica la configuración después de instalar H7, recrear backend y proxy, conservando la base activa:

```powershell
docker compose up -d --wait --force-recreate --no-deps backend frontend
```

Esto cierra sesiones. Para un arranque completo seguir el README. Los secretos se cargan en el backend al iniciar, no en Vite ni en su bundle.

En Linux se puede preparar .secrets/canales.properties con las mismas propiedades y permisos 600; copiar los nombres del ejemplo siguiente, completar valores localmente y excluirlos de Git:

```properties
taller.canales.habilitados=true
taller.canales.portal=http://localhost:8080
taller.canales.smtp.host=smtp-relay.brevo.com
taller.canales.smtp.puerto=587
taller.canales.smtp.usuario=
taller.canales.smtp.clave=
taller.canales.smtp.remitente=
taller.canales.telegram.usuario=
taller.canales.telegram.token=
```

## Confirmar canales desde el portal

1. Ingresar como Cliente → Mi cuenta → Canales de contacto.
2. Cambiar la dirección ficticia por un correo de prueba propio. A partir de ahí se ingresa con ese correo y la misma contraseña.
3. Solicitar código, copiar sus 22 caracteres del mensaje y confirmar. Si no llega, revisar correo no deseado y estado del envío; esperar un minuto antes de solicitar otro.
4. Con correo confirmado, generar enlace de Telegram, abrirlo y presionar Iniciar en el chat privado.
5. Volver al portal y usar Actualizar estado. Debe mostrar Correo confirmado y Telegram vinculado.
6. Para probar desvinculación, quitar el chat desde la misma pantalla; no depende de que Telegram esté disponible.

Generar un código o enlace nuevo invalida el anterior de cualquiera de los dos canales. Hacer la confirmación de uno antes de iniciar el otro evita reemplazar una solicitud aún necesaria.

## Comprobar avisos reales

Con una cuenta de prueba propia y ambos canales confirmados:

1. Personal publica una versión de presupuesto en una orden de ese Cliente.
2. La respuesta muestra resultado por canal. Verificar además la recepción en correo y Telegram: ENVIADO indica aceptación del proveedor.
3. Abrir el enlace al portal e ingresar para consultar/decidir.
4. Registrar fecha, canales comprobados y resultado, sin copiar destinatarios, chats ni secretos al repositorio.

La verificación real de H7 queda pendiente hasta completar este recorrido. Las pruebas con simuladores no la reemplazan.

## Problemas de configuración

- Bot sin conexión: actualizar estado luego del arranque. Revisar usuario/token en el asistente si se informa configuración incorrecta.
- Webhook activo: usar un bot dedicado y revisar su configuración. La aplicación no borra webhooks de otros servicios.
- Correo no aceptado: revisar remitente verificado, Login y clave SMTP; consultar el panel del proveedor. La aplicación no imprime la respuesta cruda ni credenciales.
- Correo cambiado: usar la dirección nueva en el próximo ingreso; la contraseña se conserva.
- Sesión vencida: ingresar otra vez. Los canales confirmados y el negocio ya guardado permanecen.
