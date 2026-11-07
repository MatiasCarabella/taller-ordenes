# Contrato de acceso y convenciones de API

Versión implementada: 0.4 — 7 de noviembre de 2026.

## Endpoints disponibles

| Método y ruta | Autorización | Resultado |
|---|---|---|
| GET /api/health | Público | Estado general del servicio; sin detalles de conexión |
| GET /api/auth/csrf | Público | `headerName` y token CSRF para la sesión del navegador |
| POST /api/auth/login | CSRF válido | JSON `correo`, `contrasena`; devuelve la sesión y rota su identificador |
| GET /api/auth/me | Sesión válida | Datos mínimos de la cuenta propia y vigencia de la sesión |
| POST /api/auth/logout | Sesión y CSRF válidos | 204; invalida sesión, contexto y token CSRF |
| GET /api/administracion/inicio | ADMINISTRACION | Contexto de la sesión de Administración |
| GET /api/personal/inicio | PERSONAL | Contexto de la sesión de Personal |
| GET /api/cliente/inicio | CLIENTE | Contexto de la sesión del Cliente |

El contexto devuelto por login, me e inicio contiene `usuario` (id, nombre, correo, perfil), `iniciadaEn`, `limiteAbsoluto` y `vencePorInactividadEn`. Las fechas son ISO 8601 UTC. No contiene hashes, huellas de credencial, contraseña, tokens de canal, costos, tarifa ni margen.

El perfil se obtiene de la BDD. Enviar otro perfil en JSON no otorga permisos. Cada área se autoriza en la API; ocultar navegación no es el control de acceso. H3 incorpora vehículos y órdenes con comprobaciones de titularidad: ver [API de operaciones](api-operaciones.md). H4 agrega [diagnóstico, presupuestos y decisiones](api-presupuestos.md). H5 incorpora [reparación, cierre, entrega y resultados](api-ejecucion.md).

## Sesión y CSRF

La sesión reside en memoria del único backend y se pierde al reiniciarlo. Su identificador se transporta por cookie HttpOnly/SameSite=Lax; en acceso remoto HTTPS se configura `SESSION_COOKIE_SECURE=true`. No se guarda autenticación en localStorage ni sessionStorage.

El token CSRF se conserva en la sesión del servidor y se entrega como valor enmascarado mediante el endpoint csrf. React lo mantiene solo en memoria y lo envía en el encabezado indicado. Login rota el ID y elimina el token CSRF anterior; la siguiente operación modificadora solicita uno nuevo. Logout lo invalida. Un rechazo CSRF permite refrescar y reintentar una vez porque ocurre antes del controlador.

Se comprueban veinte minutos sin actividad y ocho horas absolutas mediante reloj del servidor, además del timeout del contenedor web. Cada solicitud autenticada comprueba cuenta activa, perfil y huella de la credencial. La reposición de contraseña o cambio de perfil invalida autorizaciones anteriores en la siguiente solicitud, sin agregar entidades ni guardar el hash de contraseña en la sesión.

La huella interna es SHA-256 del hash Argon2id ya almacenado; no reemplaza el hash de contraseña ni se expone por API. La contraseña del principal se borra al completar la autenticación. Desactivar la cuenta también impide que sus sesiones sigan autorizando solicitudes.

## Intentos y errores

Cinco fallos por cuenta u origen en quince minutos bloquean ese criterio durante quince minutos. Hay reservas para intentos concurrentes, capacidad acotada y limpieza temporal en memoria. Un acceso exitoso no borra los fallos de otras cuentas en el mismo origen. Los contadores se pierden al reiniciar, según el alcance documentado.

El proxy reemplaza X-Forwarded-For por el origen observado y elimina el encabezado Forwarded recibido; el backend no publica un puerto al host. Tomcat interpreta las cabeceras del proxy configurado.

| HTTP | Código | Situación |
|---|---|---|
| 400 | DATOS_INVALIDOS | JSON/campos inválidos |
| 401 | CREDENCIALES_INVALIDAS | Misma respuesta para correo inexistente, cuenta inactiva o contraseña incorrecta |
| 401 | AUTENTICACION_REQUERIDA / SESION_VENCIDA / SESION_INVALIDA | No hay una sesión válida |
| 403 | CSRF_INVALIDO | Token ausente, anterior o inválido |
| 403 | ACCESO_DENEGADO | El perfil no autoriza esa acción |
| 429 | DEMASIADOS_INTENTOS | Espera indicada por Retry-After |
| 503 | SERVICIO_NO_DISPONIBLE | No se pudo acceder al servicio de datos |

Los errores usan Problem Details con `code` estable y `fieldErrors` cuando corresponde, sin reflejar la contraseña ingresada ni detalles SQL. Las respuestas de acceso no se cachean.

## Convenciones de negocio

Rutas y SQL en español sin acentos; JSON en camelCase. Fechas de BDD DATETIME(3) UTC y presentación America/Argentina/Buenos_Aires. Importes, horas y cantidades decimales se intercambian como cadenas y se calculan en servidor. Operaciones por acción, sin edición libre de estados. Los endpoints de negocio usan 404 para recurso inexistente/ajeno y 409 para conflictos de estado, versión o unicidad.

Referencias: [sesión en Spring Security](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) y [protección CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
