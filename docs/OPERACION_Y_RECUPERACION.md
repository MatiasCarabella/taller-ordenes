# Operación y recuperación

La instalación actual se ejecuta en Docker Desktop, en la máquina local. El despliegue remoto requiere un servidor, dominio y certificado propios. La superposición HTTPS se verifica en un entorno aislado; no publica automáticamente la aplicación en Internet.

## Arranque y actualización

Requisitos: Docker Desktop con contenedores Linux y Compose v2.24.4 o superior (soporte de `!override`), Git y Windows PowerShell. Java, Maven, Node, MySQL, Python y age se ejecutan dentro de contenedores.

```powershell
git clone URL_DEL_REPOSITORIO taller-ordenes
cd taller-ordenes
.\scripts\inicializar-secretos.ps1
docker compose up -d --build --wait
```

Preparar las cuentas siguiendo el README. La precarga es optativa y crea identidades ficticias inactivas; nunca se ejecuta al restaurar una base. Para detener: `docker compose stop`. Para reiniciar conservando datos: `docker compose up -d --wait`. No eliminar el volumen MySQL.

Antes de actualizar una instalación con datos, ejecutar `scripts/respaldar.ps1`, revisar el resultado, cambiar a la versión deseada y ejecutar `scripts/actualizar.ps1`. El actualizador y el respaldo comparten un bloqueo exclusivo de archivo. El respaldo mantiene además un bloqueo asesor MySQL durante el dump. Esta coordinación exige que las actualizaciones y migraciones se ejecuten mediante el actualizador; no ejecutar DDL, Flyway ni utilidades que migren el esquema por fuera de ese procedimiento mientras se respalda. La cuenta de respaldo conserva sus permisos existentes; no recibe privilegios globales adicionales.

El actualizador construye las aplicaciones antes de recrearlas y conserva el contenedor y volumen MySQL. Las migraciones validan el historial Flyway; `clean` está deshabilitado. Si una actualización cambia el esquema, recuperar código y BDD compatibles en un entorno nuevo; cambiar solo el binario por una versión anterior puede ser incompatible.

## HTTPS

En un servidor, colocar el certificado válido y su cadena en `.secrets/tls/fullchain.pem` y su clave en `.secrets/tls/privkey.pem`. Proteger la carpeta y permitir al usuario nginx del contenedor leerlos; los secretos basados en archivos conservan los permisos del host. No versionar certificados ni claves.

```powershell
$env:HTTPS_BIND='0.0.0.0'
$env:HTTPS_PORT='443'
$env:HTTP_BIND='0.0.0.0'
$env:HTTP_PORT='80'
.\scripts\actualizar.ps1 -Https
```

Mantener estas variables en la configuración protegida del host para arranques posteriores. Publicar únicamente 80/443 en el firewall, sin exponer MySQL, backend ni Docker. La configuración TLS admite TLS 1.2/1.3, redirige HTTP, aplica HSTS y activa la cookie Secure, HttpOnly y SameSite=Lax. Configurar también la URL HTTPS real del portal en los canales. Renovar el certificado mediante el proveedor del servidor y recrear frontend después de renovarlo.

Para un ensayo local usar los puertos 8088/8448 y un certificado temporal con SAN localhost/127.0.0.1. Un certificado autofirmado sirve solo para verificar la configuración de laboratorio. La redirección HTTP apunta al puerto HTTPS estándar 443; en laboratorio acceder directamente a `https://localhost:8448`.

El proxy reemplaza cabeceras de origen/esquema enviadas por el cliente. Limita login a 1 solicitud/s con ráfaga de 5, y API a 30 solicitudes/s con ráfaga de 60, por dirección de origen; el backend conserva sus límites adicionales de autenticación. El cuerpo máximo es 256 KiB. El registro del proxy contiene método, estado y duración, sin URL, query, IP, cabeceras ni cuerpo. El log de errores de nginx está deshabilitado para evitar que incluya solicitudes sensibles. Las aplicaciones se ejecutan sin root, con sistema de archivos de solo lectura y capacidades eliminadas.

## Respaldos

```powershell
.\scripts\configurar-respaldos.ps1 -DestinoLocal D:\Backups\taller-ordenes
# Opcional, con un depósito privado autorizado:
.\scripts\configurar-respaldos.ps1 -DestinoLocal D:\Backups\taller-ordenes -DestinoExterno RUTA_PRIVADA -Programar
.\scripts\respaldar.ps1
```

El asistente conserva secretos existentes y genera una identidad age dedicada en `.secrets/respaldo-age.key`. Guardar una copia de esa identidad por separado, por ejemplo en un gestor de secretos o soporte desconectado; sin ella no se recupera el respaldo. No copiarla al depósito ni a GitHub. El destinatario público se guarda en `respaldo-age.pub`. Las rutas operativas quedan en `.secrets/respaldo.json`.

La tarea Windows `TallerOrdenes-RespaldoDiario` corre a las 02:00 con la zona de Buenos Aires verificada. Usa la sesión del usuario actual, sin guardar contraseña, y requiere sesión iniciada y Docker Desktop activo. Recupera ejecuciones omitidas cuando está disponible; no garantiza el RPO durante apagados o desconexiones. Revisar `LastTaskResult`, próxima ejecución y `ultima-ejecucion.log` del depósito local. Un fallo devuelve código 1 y no rota copias. No se reemplaza esta tarea por recordatorios de la aplicación de chat.

Cada respaldo requiere un árbol Git limpio. Usa `taller_backup` con `mysqldump --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF` sobre tablas InnoDB; comprime con gzip y cifra con age. El código se conserva en un bundle Git autónomo con todas las referencias y un archivo de la versión exacta: incluye migraciones, Compose, Dockerfiles, herramientas e instrucciones. El manifiesto interno asocia commit, esquema, imagen MySQL y sumas de archivos. No incluye `.secrets`, `.env`, archivos sin seguimiento ni datos de la BDD en el paquete de código. La BDD cifrada sí contiene cuentas, hashes de acceso y datos de negocio necesarios para recuperar la instalación.

Los temporales descifrados viven únicamente en un tmpfs efímero de 1 GiB. Se verifican descifrado age, checksums SHA-256, CRC gzip e independencia del bundle antes de promover una copia. Los fingerprints de tablas permiten comprobar recuperación exacta; si hay escrituras durante su medición el respaldo se rechaza y debe repetirse en un intervalo estable. El checksum externo detecta corrupción accidental; no acredita autoría frente a alguien que pueda sustituir a la vez archivos y manifiesto. Mantener el depósito privado.

Retención: siete copias diarias locales y externas; cuatro semanales locales y externas; tres mensuales externas. Semanales y mensuales conservan la primera copia verificada del período. La rotación solo empieza después de validar las nuevas copias en todos los destinos configurados. Las carpetas `.pending-*` corresponden a intentos incompletos: revisar su causa antes de retirarlas. Copiar a una carpeta sincronizada comprueba la escritura local; la sincronización remota y los permisos del proveedor deben revisarse por separado.

El contenedor de mantenimiento monta el socket Docker durante la operación y necesita control del daemon para ejecutar dump/restauración. Se usa solo desde scripts de confianza y no es un servicio permanente ni accesible por HTTP.

## Restauración aislada

```powershell
.\scripts\restaurar.ps1 -Respaldo D:\Backups\taller-ordenes\daily\FECHA_UTC -Destino D:\Recuperaciones\ensayo -Puerto 8088
```

El destino debe ser nuevo. La herramienta valida y descifra, clona el bundle sin usar el repositorio de origen, y selecciona el commit del manifiesto. Genera credenciales MySQL nuevas en ese entorno; conserva los hashes de las cuentas importadas. Inicia una BDD vacía con volumen propio y solo acepta importar en un proyecto `taller-recuperacion-*`. Rechaza la base principal y las bases no vacías. Después compara las seis tablas, construye las aplicaciones y comprueba sus healthchecks. Los canales externos quedan deshabilitados para impedir que el ensayo compita con el bot activo o envíe avisos reales.

El resultado `recuperacion.json` registra el proyecto, puerto, código recuperado y duración hasta servicios saludables. El resultado de importación informa la antigüedad del respaldo; compararla con RPO 24 h y la duración con RTO 4 h. Las medidas de un ensayo local no garantizan recuperación de cualquier incidente.

Validar cuentas, relaciones, autorización de presupuestos, importes históricos y descargas. Para retirar el ensayo, usar exclusivamente el nombre de proyecto registrado y su Compose: `docker compose --project-directory RUTA_CODIGO -p PROYECTO_RECUPERACION down -v`. Comprobar antes que el proyecto comienza por `taller-recuperacion-`; nunca aplicar ese comando al proyecto principal.

Si se pierde el equipo, instalar Docker/Git en el reemplazo, obtener los scripts de esta versión desde GitHub o una copia del código, construir `infra/herramientas.Dockerfile`, reponer la identidad age desde su custodia separada y usar el respaldo cifrado en el procedimiento anterior. Los secretos de SMTP/Telegram se reponen aparte con el asistente. Revocar y regenerar credenciales de proveedores cuando el incidente lo requiera; no se recuperan desde el paquete de código.

Para restablecer una credencial de acceso usar `scripts/establecer-clave.ps1` desde terminal interactiva, seleccionando la cuenta y una contraseña individual. Las sesiones anteriores quedan invalidadas por el cambio. Nunca modificar ni extraer hashes para conocer una contraseña.

Referencias técnicas: [mysqldump 8.4](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html), [age](https://filippo.io/age/age.1), [límites de nginx](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html) y [Compose merge](https://docs.docker.com/reference/compose-file/merge/).
