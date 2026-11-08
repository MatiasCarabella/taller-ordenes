# Gestión de órdenes para talleres

Aplicación web para órdenes, presupuestos versionados y margen directo de un taller. Implementa acceso por perfiles, vehículos, apertura y diagnóstico, decisiones del cliente, reparación, cierre con valores históricos y entrega. Administración configura la tarifa y consulta resultados. El entorno completo y las pruebas se ejecutan con Docker. Incluye descargas privadas de presupuestos y resumen final. H7 incorpora confirmación de canales y avisos por correo/Telegram, con recepción real confirmada por el usuario. H8 incorpora respaldos cifrados y recuperación comprobada. El siguiente hito es H9: demo y cierre académico.

## Requisitos

Docker Desktop con motor Linux y Docker Compose v2.24.4 o superior (para la superposición HTTPS). No hace falta instalar Java, Maven, Node ni MySQL para construir y ejecutar el proyecto. Las pruebas también se ejecutan dentro de Docker y crean un MySQL temporal independiente.

Repositorio público: [MatiasCarabella/taller-ordenes](https://github.com/MatiasCarabella/taller-ordenes). El plan de trabajo está en [docs/PLAN_IMPLEMENTACION.md](docs/PLAN_IMPLEMENTACION.md).

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

Las FK compuestas impiden que una orden use el vehículo de otro titular o una autorización de otra orden. Existen restricciones de dominio, unicidad, tokens completos y datos de cierre. Los servicios de H3/H4 validan perfiles referenciados, titularidad, presupuestos inmutables y decisiones concurrentes; H5 garantiza transiciones y cierres consistentes, según [las decisiones iniciales](docs/decisiones-iniciales.md).

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

## Reparación, cierre y resultados

Personal abre el detalle de una orden aprobada y confirma Iniciar reparación. Durante la ejecución guarda el trabajo y declara que corresponde a la versión autorizada. Preparar cierre permite cargar costo total de repuestos y horas; Revisar cierre muestra trabajo, ingreso autorizado, tarifa vigente y costos antes de confirmar. Un presupuesto pendiente con plazo vigente impide finalizar.

La confirmación vuelve a verificar los valores. Si cambian alcance, trabajo, última propuesta o tarifa, hay que revisar otra vista previa. El cierre fija sus datos; solo queda Registrar entrega, sin reapertura ni edición histórica. Cliente consulta trabajo, fechas y venta final sin datos internos.

Administración dispone de Resultados y tarifa: configura una tarifa positiva, consulta el margen por período de finalización y despliega los valores de cada orden. La entrega no duplica resultados ni cambia su período. El reporte separa rechazadas/canceladas y calcula el porcentaje desde los totales. Administración también puede cancelar con motivo antes de iniciar reparación. Ver [API de ejecución y resultados](docs/api-ejecucion.md).

## Documentos descargables

En el detalle de la orden, desplegar una versión publicada y usar Descargar presupuesto vN (PDF). Se conservan disponibles las versiones históricas, revisadas, rechazadas o vencidas. Desde FINALIZADO/ENTREGADO, Trabajo y entrega habilita Descargar resumen final (PDF), con el trabajo final, la versión autorizada y el importe acordado al cierre.

Cliente solo descarga sus documentos; Personal y Administración consultan los del taller. Todos reciben un documento de venta sin costos internos, horas, tarifa ni margen. La API verifica pertenencia en cada solicitud y el resumen no se habilita antes de finalizar. Ver [API de documentos](docs/api-documentos.md).

Los PDF se generan desde datos persistidos, con formato A4, tipografía embebida, fechas de Buenos Aires, tablas continuadas y numeración. No hace falta instalar fuentes ni un navegador en el contenedor. Biblioteca y avisos: [dependencias PDF](docs/dependencias-pdf.md).

## Canales y avisos

Cliente → Mi cuenta → Canales de contacto permite cambiar/confirmar correo, generar un enlace de Telegram y desvincular el chat. Correo y Telegram se confirman mediante tokens aleatorios de un uso y diez minutos, guardados solo como hash. Un token nuevo reemplaza al anterior de cualquier canal. El correo de la cuenta también es su dirección de ingreso; cambiarlo conserva contraseña y perfil.

Los eventos R7 coordinan avisos después de guardar la operación y liberan la transacción antes de contactar proveedores. El actor ve resultado por canal; una falla no deshace presupuesto, decisión, cierre ni entrega. Se usan exclusivamente canales confirmados de un cliente activo. No hay historial de envíos, cola persistente ni reintento automático.

El entorno arranca con canales deshabilitados. Preparar el bot y SMTP, ejecutar scripts/configurar-canales.ps1 y seguir [la guía de configuración y prueba real](docs/CONFIGURAR_CANALES.md). Credenciales en .secrets/canales.properties, fuera de Git y del bundle. El bot se integra por long polling en el mismo backend; no requiere otro servicio. Ver [API de canales y avisos](docs/api-canales.md).

## Verificación

```powershell
./scripts/verificar.ps1
```

En Linux: `sh scripts/verificar.sh`. El script construye ambas aplicaciones y ejecuta pruebas de contraseñas y migraciones/restricciones sobre un MySQL real temporal. Testcontainers usa el socket Docker para crear/eliminar únicamente sus contenedores de prueba; no usa el volumen de la aplicación. Las pruebas no se omiten si Docker no está disponible. La suite actual tiene 131 pruebas, incluidas sesiones, CSRF, titularidad, precisión, PDF, tokens de canal, concurrencia y fallas de proveedores; ver [la evidencia de H7](docs/VERIFICACION_H7.md). SMTP/Telegram se ensayan con transportes y servidores locales de prueba. La recepción real por ambos canales fue confirmada por el usuario y se registra por separado en la evidencia de H7.

Las pruebas del esquema comprueban cinco entidades, migraciones repetibles sin cambios, dominio único, titular correcto, Administración única, tarifa por perfil, autorización de la misma orden, unicidad de versiones, respuestas completas, renglones válidos, tokens, cierres y límites decimales. H4 prueba estados, dinero y concurrencia de presupuestos sobre servicios reales; H5 verifica cierre, copia histórica de tarifa, períodos y margen.

## Operación local

```powershell
docker compose logs --tail 100 backend
docker compose stop
docker compose up -d --wait
```

Detener o bajar los contenedores sin eliminar volúmenes conserva la BDD. La eliminación de volúmenes destruye sus datos y no forma parte del arranque ni de los scripts de prueba.

Las integraciones están implementadas y verificadas con los proveedores configurados en la instalación local; una instalación nueva debe seguir la guía de configuración de canales. La [guía operativa de H8](docs/OPERACION_Y_RECUPERACION.md) documenta HTTPS, respaldos manuales cifrados, retención y recuperación; ver [evidencia](docs/VERIFICACION_H8.md). La tarea diaria y el depósito externo son opcionales y no están activados. Este arranque escucha solo en la máquina local. En remoto deberá configurarse HTTPS y `SESSION_COOKIE_SECURE=true`. No reutilizar las cuentas ficticias como identidades reales.

## Fuentes de las herramientas

- [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html) y [Spring Initializr](https://start.spring.io).
- [Flyway para MySQL](https://documentation.red-gate.com/fd/mysql-277579322.html).
- [Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/).
- [Vite](https://vite.dev/guide/).
