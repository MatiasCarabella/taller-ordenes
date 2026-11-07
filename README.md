# Gestión de órdenes para talleres

Aplicación web para órdenes, presupuestos versionados y margen directo de un taller. Implementa el entorno Docker, el esquema, la utilidad local de cuentas y el acceso real por perfiles con sesión del servidor. Incluye registro/consulta de vehículos y apertura/consulta de órdenes. Incluye diagnóstico, presupuestos completos versionados y decisión del cliente. El siguiente incremento incorpora reparación, cierre, entrega y margen.

## Requisitos

Docker Desktop con motor Linux y Docker Compose v2. No hace falta instalar Java, Maven, Node ni MySQL para construir y ejecutar el proyecto. Las pruebas también se ejecutan dentro de Docker y crean un MySQL temporal independiente.

Repositorio local: `D:/Code/taller-ordenes`. El plan de trabajo está en [docs/PLAN_IMPLEMENTACION.md](docs/PLAN_IMPLEMENTACION.md). No se creó un remoto.

## Arranque en Windows

Desde PowerShell, en la raíz del repositorio:

```powershell
./scripts/inicializar-secretos.ps1
docker compose up -d --build --wait
docker compose ps
```

En Linux: ejecutar `sh scripts/inicializar-secretos.sh` antes del mismo comando Compose. La generación conserva secretos existentes. Copiar opcionalmente `.env.example` a `.env` para cambiar la dirección/puerto local.

Abrir http://localhost:8080/ingresar. El login usa las cuentas de BDD y abre Inicio/Mi cuenta, Vehículos y Órdenes según el perfil. La disponibilidad sigue en http://localhost:8080/estado. GET `/api/health` devuelve el estado general, sin detalles de infraestructura. Ver [el contrato de acceso](docs/contrato-api.md).

Los tres servicios son:

| Servicio | Función | Exposición |
|---|---|---|
| frontend | React construido con Vite, servido por Nginx; proxy /api | 127.0.0.1:8080 |
| backend | Java 25 / Spring Boot / JDBC / Flyway | Solo redes Compose |
| db | MySQL 8.4, volumen mysql_data | Solo red interna de base |

Los Dockerfiles fijan las imágenes oficiales por digest. El frontend tiene lockfile; el backend usa Maven Wrapper y el BOM de Spring Boot. Frontend y backend se ejecutan sin root y con sistema de archivos de solo lectura, salvo temporales.

## BDD y migraciones

Flyway usa una cuenta de migraciones distinta de la aplicación. V1 crea Usuario, Vehículo, Orden, Presupuesto y Renglón; V2 agrega la FK compuesta de autorización. Flyway valida migraciones existentes y no las repite al reiniciar. No editar una migración ya aplicada: agregar otra.

Las FK compuestas impiden que una orden use el vehículo de otro titular o una autorización de otra orden. Existen restricciones de dominio, unicidad, tokens completos y datos de cierre. Los servicios de H3/H4 validan perfiles referenciados, titularidad, presupuestos inmutables y decisiones concurrentes; H5 agrega las reglas de cierre, según [las decisiones iniciales](docs/decisiones-iniciales.md).

Las cuentas MySQL de aplicación, migraciones y respaldo tienen contraseñas diferentes. Los archivos viven en `.secrets/`, excluidos de Git y del contexto Docker. La cuenta de aplicación no tiene permisos de DDL ni DELETE. El script de inicialización de usuarios se ejecuta únicamente al crear un volumen nuevo: cambiar archivos de secretos no cambia automáticamente contraseñas de una base ya inicializada.

## Cuentas ficticias y claves

Preparar datos de demostración de forma idempotente:

```powershell
docker compose run --rm --no-deps backend --spring.main.web-application-type=none --taller.cuentas=demo
```

Se crean una Administración, dos Personal y dos Clientes: `admin@taller.example`, `personal1@taller.example`, `personal2@taller.example`, `cliente1@taller.example` y `cliente2@taller.example`. Son identidades ficticias; las cuentas nuevas quedan inactivas con hashes Argon2id de contraseñas aleatorias descartadas. No existe una contraseña web de fábrica.

Establecer una contraseña individual y activar una cuenta desde una terminal interactiva:

```powershell
./scripts/establecer-clave.ps1
```

En Linux se usa `sh scripts/establecer-clave.sh`. Para empezar podés establecer la clave de `admin@taller.example` y luego la de cada perfil que necesites probar.

La clave se solicita sin mostrarla ni recibirla como argumento. Se exigen 15–128 caracteres, sin truncamiento ni reglas de símbolos, y se rechazan valores de una lista local inicial de contraseñas comunes. El hash usa Argon2id con 19 MiB, dos iteraciones y paralelismo uno. La lista se mantiene en `backend/src/main/resources/security/common-passwords.txt`.

La misma utilidad admite `--taller.cuentas=crear` y `--taller.cuentas=desactivar`, también con `-it`. La creación de otra cuenta Administración se rechaza en la BDD. La reposición de contraseña, desactivación o cambio de perfil impide que las sesiones anteriores sigan autorizando solicitudes, sin reiniciar el backend. Verificar previamente la identidad del titular y la autorización del perfil según el procedimiento del entregable. No hay alta ni recuperación pública.

## Vehículos y apertura de órdenes

Personal registra/selecciona vehículos y abre órdenes. Administración consulta todos los registros del taller. Cliente consulta únicamente Mis vehículos y Mis órdenes. Para probar el recorrido, habilitar las cuentas Personal y Cliente necesarias con el mismo script de claves; una cuenta Administración no reemplaza al perfil Personal.

Desde Personal: Vehículos → Registrar vehículo → elegir cliente activo y guardar → Abrir orden → responsable activo y motivo → Confirmar apertura. La orden queda Recibido. Un dominio repetido devuelve la ficha existente y conserva su titular. Los listados permiten búsquedas y paginación; las fichas enlazan vehículo y sus órdenes.

Ver [los endpoints y reglas de operaciones](docs/api-operaciones.md). No hay edición de titular ni escritura libre de estado. El diagnóstico y los presupuestos están disponibles en el detalle de cada orden.

## Diagnóstico y presupuestos

Desde Personal: Órdenes → detalle → Guardar diagnóstico → Preparar presupuesto → cargar alcance completo, renglones y vencimiento → Revisar presupuesto → Confirmar publicación. Se puede corregir el diagnóstico hasta publicar la primera versión. Cada versión publicada queda fija. El borrador no se guarda al salir.

Desde Cliente: Mis órdenes → detalle → aceptar, rechazar o solicitar revisión de la última versión pendiente; confirmar la decisión. Solo el titular responde, una vez y antes del vencimiento. La pantalla distingue la última propuesta del alcance autorizado. Un adicional rechazado, revisado o vencido conserva la aceptación anterior; aceptar la nueva versión sustituye esa autorización. Administración consulta el historial.

Cada propuesta incluye todo el alcance; la nueva versión parte de la última publicada para facilitar su revisión. Los importes se calculan en servidor con decimales y HALF_UP por renglón. Los plazos se ingresan y muestran en hora de Buenos Aires. Si otra operación cambia la versión o autorización, se debe actualizar y revisar antes de publicar. Ver [API de presupuestos](docs/api-presupuestos.md).

## Verificación

```powershell
./scripts/verificar.ps1
```

En Linux: `sh scripts/verificar.sh`. El script construye ambas aplicaciones y ejecuta pruebas de contraseñas y migraciones/restricciones sobre un MySQL real temporal. Testcontainers usa el socket Docker para crear/eliminar únicamente sus contenedores de prueba; no usa el volumen de la aplicación. Las pruebas no se omiten si Docker no está disponible. La suite actual tiene 55 pruebas, incluidas sesiones, CSRF, permisos, titularidad, precisión monetaria, vencimientos y publicaciones/decisiones concurrentes; ver [la evidencia de H4](docs/VERIFICACION_H4.md).

Las pruebas del esquema comprueban cinco entidades, migraciones repetibles sin cambios, dominio único, titular correcto, Administración única, tarifa por perfil, autorización de la misma orden, unicidad de versiones, respuestas completas, renglones válidos, tokens, cierres y límites decimales. H4 prueba estados, dinero y concurrencia de presupuestos sobre servicios reales; H5 incorpora el cierre y sus cálculos.

## Operación local

```powershell
docker compose logs --tail 100 backend
docker compose stop
docker compose up -d --wait
```

Detener o bajar los contenedores sin eliminar volúmenes conserva la BDD. La eliminación de volúmenes destruye sus datos y no forma parte del arranque ni de los scripts de prueba.

El acceso remoto HTTPS, integraciones, respaldo externo y ensayo de recuperación corresponden a hitos posteriores; este arranque escucha solo en la máquina local. En remoto deberá configurarse HTTPS y `SESSION_COOKIE_SECURE=true`. No reutilizar las cuentas ficticias como identidades reales.

## Fuentes de las herramientas

- [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html) y [Spring Initializr](https://start.spring.io).
- [Flyway para MySQL](https://documentation.red-gate.com/fd/mysql-277579322.html).
- [Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/).
- [Vite](https://vite.dev/guide/).
