# API de canales y avisos

Implementación: H7 — 8 de noviembre de 2026. Validación local completada; recepción con proveedores reales pendiente.

## Canales propios

Solo CLIENTE, con sesión y CSRF en POST. El ID siempre proviene de la sesión; no existe administración de canales ajenos.

| Método y ruta | Resultado |
|---|---|
| GET /api/canales | Correo, confirmación, vínculo Telegram, solicitud pendiente/vencimiento y disponibilidad de proveedores |
| POST /api/canales/correo | Cambia correo propio; cuerpo correo |
| POST /api/canales/correo/codigo | Emite y envía código; no devuelve el código |
| POST /api/canales/correo/confirmacion | Consume código; cuerpo codigo |
| POST /api/canales/telegram/enlace | Devuelve enlace privado de un uso y venceEn |
| POST /api/canales/telegram/desvincular | Quita chat y cancela cualquier solicitud pendiente |

Cambiar correo también cambia la dirección de ingreso. Conserva contraseña y perfil; invalida confirmación y token pendiente. Se conserva la unicidad y una dirección idéntica, sin distinguir mayúsculas, no revoca la confirmación.

Correo usa 16 bytes aleatorios, codificados como 22 caracteres base64url. Telegram usa 32 bytes, 43 caracteres, compatibles con el enlace start. Ambos duran diez minutos, con comparación estricta ahora < vencimiento y precisión de milisegundos.

Usuario conserva únicamente SHA-256 del token, finalidad y vencimiento. Un nuevo código/enlace reemplaza al anterior, incluso del otro canal. Confirmación y vinculación bloquean la fila y consumen el token en la misma transacción. Los códigos de correo se limitan a uno por minuto y cuenta, con memoria acotada; no se agrega una entidad de intentos ni historial.

Telegram recibe mensajes mediante long polling en el backend. No hay endpoint público de vinculación: el chat debe ser privado, emisor humano y chat.id = from.id positivo. El token vigente identifica la cuenta que solicitó el enlace. Un chat ya asociado a otra cuenta se rechaza sin consumir el token perdedor; la unicidad de BDD resuelve carreras.

Antes de ofrecer un enlace se verifica identidad del bot mediante getMe y ausencia de webhook. No se elimina un webhook existente. El offset se mantiene en memoria; la unicidad de consumo evita volver a vincular mediante un mensaje repetido.

La vista no expone hash, chat ID, credenciales o códigos. El enlace en claro solo se devuelve a su solicitante; no se reconstruye desde el hash al recargar. Actualizar estado es una acción explícita; no hay consultas periódicas desde el navegador que prolonguen artificialmente la sesión.

## Avisos de negocio

Publicación de presupuesto y cambios a APROBADO, EN_REPARACION, FINALIZADO, ENTREGADO, RECHAZADO y CANCELADO. Las respuestas que mantienen estado, como revisión o aceptación de un adicional durante reparación, no inventan un evento. Entrega repetida tampoco reenvía.

Los servicios devuelven datos y evento interno después de terminar su transacción. El controlador coordina los proveedores después del commit y la liberación de la conexión. Un fallo de consulta o envío devuelve informe y conserva el negocio.

Los cuerpos JSON existentes conservan sus campos y agregan avisos, o null si no corresponde evento. Informe: canales, lista con canal, estado, intentado y mensaje. No incluye destinatarios ni errores crudos del proveedor.

| Estado | Significado |
|---|---|
| SIN_CANAL | Cliente sin ese canal confirmado, o sin destinatario activo |
| NO_CONFIGURADO | Canal confirmado, proveedor no habilitado por el taller |
| ENVIADO | Proveedor aceptó la solicitud; no prueba recepción en bandeja/dispositivo |
| FALLO | No se pudo confirmar el resultado; puede existir entrega tardía |
| OCUPADO | No se inició porque se alcanzó el límite de operaciones en curso |

Correo y Telegram se intentan independientemente, con hasta dieciséis tareas en curso y espera conjunta de ocho segundos para informar al actor. No hay cola de envíos ni reintento persistente. Un envío que ya estaba en curso puede terminar después del límite; no se cancela para reintentarlo ni se duplica automáticamente. El portal muestra siempre el dato guardado.

SMTP requiere TLS con comprobación del servidor: STARTTLS obligatorio en 587/2525 o TLS implícito en 465; conexión/lectura/escritura con timeout de tres segundos. Telegram usa HTTPS, sin redirecciones: cuatro segundos para consultas cortas/envíos y veinticuatro para long polling de veinte segundos.

Mensajes mínimos: número de orden, cambio y enlace al portal, sin precios, trabajo libre, costos, margen, credenciales ni tokens de sesión. Las decisiones se realizan autenticadas en el portal. La verificación de correo envía su código al correo propio aún no confirmado para comprobar control; los avisos de negocio solo usan canales confirmados.

## Errores de canal

400 CODIGO_INVALIDO; 409 CORREO_NO_DISPONIBLE / CORREO_YA_CONFIRMADO; 429 CODIGO_SOLICITADO_RECIENTEMENTE; 503 SMTP_NO_CONFIGURADO / TELEGRAM_NO_CONFIGURADO / TELEGRAM_NO_DISPONIBLE / CANAL_OCUPADO. Se conservan los errores de sesión, perfil, CSRF y recurso.

Ver [configuración local y prueba real](CONFIGURAR_CANALES.md).
