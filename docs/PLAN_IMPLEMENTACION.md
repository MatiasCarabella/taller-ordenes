# Plan de implementación del sistema de gestión de órdenes

Fecha de inicio del plan: 7 de noviembre de 2026.
Estado: H0–H8 completos para el alcance local acordado. Respaldos manuales y recuperación comprobada; HTTPS remoto y programación documentados. Siguiente hito: H9, demo y cierre académico.

Este archivo guía la construcción real del proyecto y, después, la preparación de la demo. Las casillas se completan al verificar resultados, con referencia al commit y a la evidencia correspondiente.

## 1. Base y estrategia

La fuente funcional es la [versión actual del entregable 4](https://docs.google.com/document/d/12HBhai1Tq8EULD5z6A0KcAfBXjziNQwpaRPJk9LxMU8/edit?tab=t.0), revisada al preparar este plan: reglas R1–R7, quince historias de usuario, diccionario de datos, seguridad y recuperación.

La secuencia propuesta es **base técnica y BDD → backend mínimo → frontend temprano → recorridos completos → integraciones → despliegue y recuperación → demo**. Dentro de cada recorrido se avanza BDD → backend → frontend → pruebas. Así, la primera pantalla útil aparece en el primer incremento y los problemas de uso se detectan antes de completar toda la API.

El primer incremento conserva el objetivo de HU-01, HU-11 y HU-02 del documento: iniciar sesión, registrar o seleccionar vehículo y abrir una orden persistente. Las 28 horas del sprint documentado son una referencia de alcance; se registrarán horas reales y se ajustarán las estimaciones posteriores. No se trasladan las fechas de octubre a este nuevo seguimiento.

### Alcance que se conserva

- Un taller; perfiles `ADMINISTRACION`, `PERSONAL` y `CLIENTE`; cuentas precargadas y una única cuenta Administración para la demostración.
- Cinco entidades funcionales: Usuario, Vehículo, Orden, Presupuesto y Renglón. Las tablas técnicas de migraciones no amplían el dominio.
- Presupuestos publicados completos, versionados e inmutables; una autorización operativa por orden.
- Cierre con costos agregados declarados y margen directo; sin inventario, cobros, facturación, contabilidad ni utilidad neta.
- Acceso con sesión del servidor; sin alta pública, recuperación pública de contraseña, reapertura de cierres ni cambio de titular del vehículo.
- Portal como fuente de información actual. Correo y Telegram avisan; las decisiones se realizan autenticadas en el portal.
- Sin historial de estados, historial de tarifas, historial de envíos ni cola persistente de reintentos.

### Decisiones técnicas propuestas

| Componente | Propuesta y motivo |
|---|---|
| Backend | Java 25, Spring Boot, Spring MVC y Spring Security, respetando el stack documentado. Una sola aplicación, organizada por funcionalidad. |
| Persistencia | MySQL 8.4/InnoDB y Spring JDBC, con SQL explícito para restricciones, consultas y bloqueos. No hace falta incorporar un ORM para estas cinco entidades. |
| Evolución del esquema | Migraciones SQL con Flyway. Verificar y fijar una combinación compatible con MySQL 8.4, incluyendo el módulo MySQL requerido por Flyway. |
| Frontend | React, TypeScript y Vite. Rutas por perfil, componentes reutilizables y estado local al principio; incorporar más herramientas solo si resuelven una necesidad concreta. |
| Contrato | API JSON bajo `/api`, DTO explícitos y contrato versionado. No devolver directamente filas de BDD con campos internos. |
| Importes | `BigDecimal` y `HALF_UP` en backend; decimales como cadenas en JSON para evitar pérdidas de precisión. El frontend muestra resultados calculados por el servidor. |
| Pruebas | Pruebas unitarias para reglas y cálculo; integración sobre MySQL real con Testcontainers; unas pocas pruebas de navegador sobre recorridos críticos. |
| Ejecución | Compose con tres servicios: frontend/proxy, backend y MySQL. En desarrollo, Vite puede ejecutarse localmente con proxy `/api`. |
| Integraciones | Adaptadores pequeños para SMTP y Telegram, intercambiables por simuladores en pruebas. No requieren servicios adicionales de aplicación. |

Las versiones exactas, imágenes y dependencias se fijan en H0 tras comprobar compatibilidad. Se usarán Maven Wrapper y el archivo de bloqueo de npm; no etiquetas de imagen `latest`. Las elecciones de Spring JDBC, Flyway y Testcontainers son propuestas de implementación, no requisitos ya agregados al entregable.

## 2. Seguimiento por hitos

| Hito | Resultado verificable | Dependencia | Estado |
|---|---|---|---|
| H0 | Entorno y esqueleto reproducibles | — | Completado |
| H1 | Esquema íntegro y datos ficticios | H0 | Completado |
| H2 | Acceso real desde la interfaz | H1 | Completado |
| H3 | Vehículo y orden de extremo a extremo | H2 | Completado |
| H4 | Diagnóstico, presupuesto y autorización | H3 | Completado |
| H5 | Reparación, cierre, entrega y margen | H4 | Completado |
| H6 | Documentos descargables | H4; resumen requiere H5 | Completado |
| H7 | Canales confirmados y avisos reales | H5 | Completo: recepción real confirmada por el usuario |
| H8 | Despliegue, respaldo y restauración comprobados | H6 y H7 | Completo en Docker local; sin job diario por decisión del usuario |
| H9 | Demo y apartado académico con evidencia real | H8 | Pendiente |

### H0. Preparar el entorno y el proyecto

- [x] Elegir una carpeta local de código fuera del árbol sincronizado de OneDrive; conservar esta carpeta para documentación y paquetes de entrega. Inicializar Git local. La creación de un repositorio remoto se realizará cuando se acuerde su destino.
- [x] Comprobar JDK 25, Node compatible con Vite, Docker/Compose, memoria y espacio para imágenes, volúmenes y pruebas de MySQL.
- [x] Fijar versiones compatibles y documentar los comandos de construcción y ejecución.
- [x] Crear la estructura del repositorio indicada más abajo, backend vacío, frontend mínimo, `.gitignore` y ejemplos de configuración sin secretos.
- [x] Preparar Compose, red privada, volumen MySQL, comprobaciones de disponibilidad y usuarios separados de aplicación, migraciones y respaldo con permisos acordes a cada tarea.
- [x] Exponer una comprobación mínima de salud y mostrar desde el frontend que alcanza al backend por `/api`.
- [x] Definir convenciones de nombres, errores, fechas UTC, formato decimal y contrato de autenticación.

**Cierre:** un checkout limpio construye y arranca siguiendo el README; frontend y backend se comunican; ningún secreto está en Git. MySQL no publica puertos en la configuración de despliegue.

### H1. Implementar la BDD y sus migraciones

- [x] Convertir el diccionario en migraciones: Usuario → Vehículo → Orden sin autorización → Presupuesto → Renglón → FK de autorización de Orden. Resolver la dependencia circular con una migración posterior que agrega la restricción.
- [x] Usar los tipos acordados: ID/FK positivos, importes `DECIMAL(12,2)`, horas/cantidades `DECIMAL(8,2)` y fechas `DATETIME(3)` UTC.
- [x] Incorporar unicidad de correo, chat de Telegram cuando existe, dominio, `(orden_id, version)` y `(presupuesto_id, numero)`.
- [x] Incorporar las FK compuestas para vehículo/titular de orden y presupuesto autorizado/orden, con sus claves únicas de soporte.
- [x] Agregar restricciones locales de valores, estados y nulabilidad compatibles con el diccionario. Documentar qué invariantes entre filas o tablas se garantizan en servicios transaccionales.
- [x] Crear índices para órdenes por cliente, responsable, estado y `finalizado_en`, evitando índices redundantes con las claves existentes.
- [x] Preparar datos ficticios: una Administración, dos cuentas Personal y al menos dos clientes para probar propiedad de recursos. Precargar la tarifa ilustrativa de ARS 6.000/h sin claves fijas en el repositorio.
- [x] Construir la utilidad local de cuentas: contraseña solicitada sin eco, hash Argon2id, asignación de perfil autorizado, activación y reposición técnica. Separar datos de demostración de migraciones de estructura.
- [x] Probar migración desde cero, arranque repetido sin duplicados, restricciones y permanencia de datos tras reiniciar contenedores.

**Cierre:** la base se reconstruye con migraciones; rechaza dominios/versiones duplicados y referencias cruzadas inválidas. El esquema no depende de cambios manuales realizados desde un cliente SQL.

### H2. Autenticación, permisos y primera interfaz — HU-01

- [x] Implementar login, sesión actual y logout; cuentas inactivas y credenciales incorrectas reciben un error genérico.
- [x] Aplicar contraseña de 15–128 caracteres, lista local de contraseñas comunes y Argon2id con parámetros iniciales del documento; medir su costo en el entorno real.
- [x] Configurar rotación del identificador de sesión, veinte minutos de inactividad, ocho horas máximas, invalidación en logout/reposición y sesiones en memoria para el único backend.
- [x] Implementar límite temporal de cinco fallos por cuenta/origen en quince minutos y espera de quince minutos, conforme al alcance documentado.
- [x] Integrar cookie de sesión `HttpOnly`, `SameSite=Lax` y `Secure` en remoto. Mantener CSRF activo y resolver su renovación después de login/logout en la SPA; no guardar credenciales ni sesión en `localStorage`.
- [x] Definir autorización por perfil y propiedad en la API. Elegir DTO distintos cuando cambian los datos visibles; el cliente no recibe costos, tarifa interna ni margen.
- [x] Construir login, estructura de navegación por perfil, manejo de sesión vencida y mensajes de error desde el frontend.

**Cierre:** los tres perfiles acceden desde el navegador; las peticiones directas a la API también respetan permisos. Se prueban cuenta inactiva, espera por fallos, CSRF, expiración y cierre de sesión.

### H3. Vehículos y apertura de órdenes — HU-11 y HU-02

- [x] Registrar/buscar vehículo con dominio normalizado a mayúsculas sin espacios ni guiones; ante duplicado, mostrar la ficha existente.
- [x] Seleccionar cliente precargado activo y validar marca/modelo. Mantener fija la titularidad.
- [x] Abrir una orden `RECIBIDO` con vehículo, motivo y responsable Personal activo. Derivar el cliente del vehículo; no aceptar un titular distinto enviado por el navegador.
- [x] Implementar listados y detalle según perfil: Personal opera; Cliente solo consulta sus órdenes. La autorización se aplica también a cada consulta individual.
- [x] Construir el formulario de vehículo, apertura, listado y detalle básico usando los bocetos del documento.
- [x] Probar duplicados concurrentes, titular incorrecto, responsable inactivo, acceso a una orden ajena y persistencia después de reiniciar.

**Cierre del primer incremento:** desde la interfaz se inicia sesión, se registra o selecciona vehículo y se abre una orden persistente. No se duplica dominio ni se acepta un titular incorrecto. HU-01, HU-11 y HU-02 tienen sus criterios demostrados.

### H4. Diagnóstico, presupuestos y decisiones — HU-03, HU-04 y HU-05

- [x] Guardar diagnóstico, autor y fecha; permitir correcciones solo hasta la primera publicación.
- [x] Publicar una versión completa, con al menos un renglón, cantidades positivas, precios no negativos, vencimiento futuro y total calculado en servidor. No persistir borradores de presupuesto.
- [x] Generar versiones correlativas bajo bloqueo de la orden. Conservar inmutables contenido, total y renglones publicados.
- [x] Mostrar al cliente versiones históricas, última versión publicada y autorización operativa como conceptos distintos.
- [x] Implementar aceptar/rechazar/solicitar revisión: solo titular, última versión pendiente, una vez y antes de vencer. Usar un reloj inyectable para probar límites temporales.
- [x] Validar y actualizar decisión, decisor, fecha y autorización dentro de una sola transacción con bloqueo de la orden, compartido por publicación y decisión.
- [x] Aplicar el efecto de rechazo/revisión inicial y de adicionales: un rechazo inicial cierra sin reparación; rechazar o revisar un adicional conserva la autorización anterior.
- [x] Construir pantallas de diagnóstico, edición previa a publicación, consulta de versiones y decisión autenticada. Los estados del servidor gobiernan las acciones disponibles.
- [x] Probar dos decisiones simultáneas, publicación concurrente con aceptación, versión sustituida, vencimiento exacto, respuesta repetida e intento de modificar una versión publicada.

**Cierre:** un adicional sin aceptar nunca habilita trabajo nuevo; una aceptación anterior sigue operativa aunque venza su plazo de respuesta. No pueden quedar dos autorizaciones operativas actuales por una carrera entre solicitudes.

### H5. Reparación, cierre y resultados — HU-06, HU-08, HU-09, HU-10, HU-13 y HU-15

- [x] Implementar transiciones explícitas por acción y perfil, basadas en la Tabla 8. No exponer una actualización libre del campo `estado`.
- [x] Iniciar reparación solo con autorización aceptada de la misma orden; guardar trabajo y actualización durante `EN_REPARACION` sin habilitar adicionales no aceptados.
- [x] Permitir a Administración configurar tarifa positiva. Mantener fija la tarifa ya copiada en cierres anteriores.
- [x] Implementar previsualización de cierre sin persistirlo y confirmación transaccional. Revalidar condiciones y recalcular en servidor al confirmar; informar cualquier cambio relevante frente a la previsualización antes de cerrar.
- [x] Exigir autorización, trabajo final, costos/horas no negativos, tarifa válida y ausencia de propuestas pendientes no vencidas. Copiar ingreso y tarifa al cierre bajo una lectura consistente; fijar `finalizado_en` e inmovilizar sus datos.
- [x] Calcular CM, M y M% según R4, con redondeo decimal; permitir margen negativo y mostrar «No aplica» cuando el ingreso es cero. No agregar columnas para resultados derivados.
- [x] Registrar entrega sin cambiar fecha ni valores del cierre; una repetición no duplica resultados.
- [x] Permitir cancelación por Administración solo antes de reparar, con motivo y fecha; mantener rechazo/cancelación sin datos económicos de cierre.
- [x] Consultar margen por orden y período solo para Administración: `FINALIZADO` y `ENTREGADO` una vez, por `finalizado_en`; conteos separados de rechazadas/canceladas.
- [x] Convertir el período local a intervalo UTC semiabierto: inicio incluido y comienzo del día siguiente al final excluido. Calcular el porcentaje global desde los totales.
- [x] Completar pantallas de ejecución, previsualización, cierre, entrega, cancelación y resultados con filtros por fecha.

**Cierre:** el recorrido completo funciona desde la interfaz y el servidor impide transiciones, cierres y ediciones inválidas. El caso I=70.000, CR=25.000, H=2 y T=6.000 devuelve CM=12.000, M=33.000 y M%=47,14. Cambiar la tarifa no modifica ese cierre.

### H6. Documentos descargables — HU-14

- [x] Implementar descarga de presupuestos publicados y resumen desde `FINALIZADO`, identificando la versión autorizada.
- [x] Generar los documentos con los datos persistidos de la versión/cierre correspondiente, sin depender de lo mostrado en el navegador.
- [x] Aplicar propiedad del recurso en cada descarga y excluir costos internos, tarifa y margen de los documentos del cliente.
- [x] Elegir una biblioteca PDF mantenida con licencia compatible, fijar su versión y comprobar saltos de página, acentos, versiones con muchos renglones y totales.
- [x] Añadir descargas a la interfaz y verificar autorización por solicitud directa.

**Cierre:** un cliente obtiene sus documentos con contenido histórico correcto; no obtiene documentos ajenos ni datos económicos internos.

### H7. Confirmación de canales y avisos — HU-12 y HU-07

- [x] Crear adaptadores SMTP/Telegram y simuladores de prueba; definir tiempos máximos de conexión/respuesta y resultados por canal.
- [x] Confirmar correo mediante código aleatorio de un uso y diez minutos; un cambio de dirección invalida su confirmación y mantiene la unicidad.
- [x] Generar enlace de Telegram con token aleatorio de un uso y diez minutos. Guardar solo hash, finalidad y vencimiento en Usuario; un nuevo token invalida el anterior, incluso si era para el otro canal.
- [x] Recibir `/start <token>` y vincular únicamente un chat privado controlado por el cliente, con unicidad, consumo transaccional del token y opción de desvincular.
- [x] Usar inicialmente long polling de Telegram dentro del único backend, sin un servicio de bot separado. No combinarlo con webhook. Mantener las decisiones de presupuestos en el portal.
- [x] Disparar avisos de los eventos de R7 después del commit del negocio, mediante un coordinador simple con tiempos acotados, sin cola persistente. La respuesta al actor informa intento/fallo/sin canal por canal; un fallo externo no revierte el negocio.
- [x] Enviar mensajes mínimos con enlace al portal, sin tokens de sesión, costos internos ni margen. Usar HTTPS y SMTP con TLS; mantener credenciales fuera de Git y del bundle del frontend.
- [x] Construir gestión de canales, estados de confirmación y avisos de resultado. El portal sigue usable sin canales o con proveedores caídos.
- [x] Probar tokens vencidos/reutilizados, finalidad incorrecta, vinculación concurrente, chat ya asociado, ausencia de canal, un canal fallido y caída de ambos proveedores.

- [x] Completar configuración local, confirmación y recepción real de correo/Telegram con una cuenta de prueba autorizada; registrar resultado.

**Cierre:** se verifica un correo y un Telegram reales con cuentas de prueba autorizadas. Los simuladores sirven para repetir fallos; la evidencia distingue qué se probó con proveedores reales y qué se simuló. No se añade historial de envíos ni reintento persistente.

### H8. Despliegue, seguridad y recuperación

Alcance acordado: Docker local. HTTPS remoto queda preparado y verificado en laboratorio; no se publica un servidor. El usuario pidió respaldos manuales e instrucciones de programación, sin registrar el job diario ni configurar un depósito externo.

- [x] Construir frontend y backend con herramientas fijadas y ejecutar los tres contenedores; proxy con `/api` y MySQL privado.
- [x] Preparar HTTPS remoto y comprobar TLS, cookies seguras, límites por origen, secretos protegidos y registros sin contenido sensible en entorno aislado.
- [x] Revisar matriz perfil/recurso/acción: 131 pruebas y cincuenta comprobaciones adicionales sobre proxy HTTPS.
- [x] Implementar respaldo InnoDB consistente, gzip, checksum y cifrado age con usuario separado; coordinar migraciones/actualizaciones mediante bloqueo compartido y procedimiento documentado, sin DDL manual durante la copia.
- [x] Documentar programación a las 02:00 de Buenos Aires y depósito privado externo opcionales; implementar y probar retención de siete diarias, cuatro semanales y tres mensuales, con copia semanal local y rotación posterior a validación. Mantener ejecución manual según lo solicitado.
- [x] Generar paquete independiente de código con bundle Git, commit, migraciones, herramientas fijadas, Compose, instrucciones y manifiesto; cifrar y recuperar sin montar el repositorio original. Excluir secretos y datos personales del paquete de código.
- [x] Restaurar código y BDD compatibles en entornos limpios; comparar seis tablas, cuentas, relaciones y presupuesto autorizado; comprobar caso conocido de margen de 68.000.
- [x] Medir recuperación: 70,845 s y 67,408 s hasta servicios saludables; respaldos de 88,118 s y 36,234 s al importar. Diferenciar estas mediciones de objetivos RTO 4 h/RPO 24 h continuos.
- [x] Documentar y ensayar arranque, actualización, apagado, restauración y reposición técnica de credenciales; añadir protección contra confusión de proyectos y bases no vacías.

**Cierre:** operación y recuperación locales comprobadas. Evidencia: docs/VERIFICACION_H8.md; procedimientos: docs/OPERACION_Y_RECUPERACION.md. El despliegue remoto y la activación de tareas/depósito externo requieren su configuración específica y no se presentan como realizados.

### H9. Preparar la demo y cerrar la entrega

- [ ] Congelar una versión etiquetada y preparar un conjunto ficticio restaurable para repetir el recorrido sin inconsistencias.
- [ ] Ensayar el guion indicado abajo con tres perfiles y comprobar que funciona tras reiniciar.
- [ ] Preparar acceso al recurso real, instrucciones, código y material audiovisual que requiera la consigna. Definir el destino y acceso antes de publicar o compartir recursos.
- [ ] Registrar versión, configuración, pruebas realizadas y evidencia real; distinguir simulaciones de integraciones verificadas.
- [ ] Incorporar el apartado Demo después de Conclusiones y antes de Referencias con el enlace real. Ajustar diagramas o descripciones si alguna decisión implementada difiere de la especificación.
- [ ] Actualizar índices y verificar el PDF académico final después de incorporar la demo.

**Cierre:** otra persona puede ejecutar o acceder al recorrido documentado con instrucciones reproducibles. La entrega describe la versión efectivamente mostrada.

## 3. Organización inicial del repositorio

```text
taller-ordenes/
├── backend/                 # Java, Maven Wrapper, código y pruebas
│   └── src/main/resources/db/migration/
├── frontend/                # React, TypeScript, Vite y lockfile
├── infra/                   # Dockerfiles, proxy, Compose y ejemplos sin secretos
├── scripts/                 # Cuentas, respaldo, recuperación y preparación de demo
├── docs/                    # Contrato API, decisiones y guía de ejecución
├── compose.yaml
├── .gitignore
└── README.md
```

El backend se organiza por funcionalidades —acceso, usuarios, vehículos, órdenes, presupuestos, resultados, documentos y canales— con controladores, servicios y consultas dentro de cada área cuando hagan falta. Las transacciones y reglas pertenecen a los servicios; el frontend no decide autorizaciones ni importes oficiales. Se registran solo decisiones técnicas relevantes, sin incorporar microservicios, bus de eventos o repositorios genéricos por anticipado.

## 4. Matriz de cobertura

| Historia | Implementación principal | Evidencia mínima |
|---|---|---|
| HU-01 | H1–H2 | Acceso por perfil, sesión y denegación en API |
| HU-02 | H3 | Alta persistente con vehículo/titular correctos |
| HU-03 | H4 | Diagnóstico corregible antes de publicar e inmóvil después |
| HU-04 | H4 | Versiones completas, correlativas e inmutables |
| HU-05 | H4 | Una decisión válida; concurrencia y adicionales |
| HU-06 | H5 | Inicio y avance solo con alcance autorizado |
| HU-07 | Consulta en H3–H5; avisos en H7 | Portal propio y resultados de envío sin rollback |
| HU-08 | H5 | Previsualización, confirmación e inmutabilidad |
| HU-09 | H5 | Cálculo conocido, período y exclusiones |
| HU-10 | H1 y H5 | Tarifa positiva e históricos sin cambios |
| HU-11 | H3 | Dominio único y titular fijo |
| HU-12 | H7 | Confirmación, uso único, vencimiento y desvinculación |
| HU-13 | H5 | Entrega sin alterar ni duplicar cierre |
| HU-14 | H6 | Descarga propia y sin datos internos |
| HU-15 | H5 | Cancelación previa a reparación, motivo y exclusión de margen |

R1–R3 se verifican en H4/H5; R4–R6 en H5; R7 en H7. Seguridad se construye desde H1/H2 y se verifica nuevamente en H8, en lugar de agregarse al final.

## 5. Pruebas que protegen las decisiones del proyecto

| Área | Casos obligatorios |
|---|---|
| Autorización | Orden/documento ajenos; perfil manipulado en una petición; intento del cliente de acceder a costos; Personal sin acceso a reportes de margen ni cambio de tarifa. |
| Presupuestos | Última versión sustituida; vencimiento exacto; decisión repetida; publicación y aceptación simultáneas; adicional rechazado/revisado que conserva autorización anterior. |
| Cierre | Sin tarifa; sin autorización; adicional pendiente vigente; dos cierres simultáneos; edición posterior; cambio de tarifa concurrente con cierre. |
| Precisión | Subtotales redondeados antes de sumar; `HALF_UP`; ingreso cero; margen negativo; límites de decimales y desbordamientos rechazados. |
| Período | Inicio incluido, día posterior excluido y conversión Buenos Aires/UTC; porcentaje desde totales; FINALIZADO/ENTREGADO sin duplicación. |
| Canales | Token vencido/repetido/de finalidad distinta; nuevo token invalida anterior; falla de un proveedor después del commit; portal sin canales confirmados. |
| Persistencia | Reinicio, unicidad, FK compuestas, migración limpia y restauración de una versión compatible. |

Se prueba la lógica monetaria sin depender de la interfaz y las transacciones sobre MySQL, especialmente las carreras entre solicitudes. No se reemplazan estas verificaciones con una base en memoria de comportamiento distinto. En navegador se automatizan los recorridos principales; no cada detalle visual.

## 6. Criterio de terminado por hito

Un hito se marca completo cuando cumple sus criterios funcionales y, si corresponde, puede recorrerse desde la interfaz; pasan las pruebas asociadas; los errores se presentan de forma útil; no se filtran datos entre perfiles; las migraciones arrancan desde un entorno limpio; y el README refleja lo necesario para ejecutarlo. Se registra un commit y evidencia breve. Los simuladores o formularios desconectados no cierran una historia de extremo a extremo.

Al finalizar cada sesión se actualizan las casillas, el resultado comprobado y el siguiente paso. Las diferencias relevantes con el entregable se registran y se concilian antes de preparar la demo.

| Fecha | Hito/tareas | Resultado comprobado | Commit/evidencia | Siguiente paso |
|---|---|---|---|---|
| 07/11/2026 | Preparación del plan | Alcance, secuencia y criterios definidos; sin código implementado | Este archivo | H0 |
| 07/11/2026 | H0 y H1 | Tres servicios saludables; esquema y precarga idempotente; 12 pruebas sin fallos y persistencia tras reinicio | D:/Code/taller-ordenes/docs/VERIFICACION_H0_H1.md (estado local) | H2: login, sesión y permisos |
| 07/11/2026 | H2 / HU-01 | Login, sesión, CSRF, perfiles y revocación; 26 pruebas sin fallos y navegador real con tres perfiles | D:/Code/taller-ordenes/docs/VERIFICACION_H2.md (estado local) | H3: vehículos y órdenes |
| 07/11/2026 | H3 / HU-11 y HU-02 | Vehículos, órdenes, titularidad y concurrencia; 37 pruebas y navegador aislado con reinicio | Etiqueta local h3-base-operativa; docs/VERIFICACION_H3.md | H4: diagnóstico y presupuestos |
| 07/11/2026 | H4 / HU-03, HU-04 y HU-05 | Diagnóstico, versiones completas, decisiones y autorización; 55 pruebas y navegador aislado con revisión, aceptación, adicional rechazado y reinicio | Etiqueta local h4-presupuestos; docs/VERIFICACION_H4.md | H5: reparación, cierre, entrega y margen |
| 07/11/2026 | H5 / HU-06, HU-08, HU-09, HU-10, HU-13 y HU-15 | Reparación, cierre histórico, entrega, cancelación, tarifa y margen; 80 pruebas y navegador aislado con cambio de tarifa, adicional, privacidad y reinicio | Etiqueta local h5-cierre-resultados; docs/VERIFICACION_H5.md | H6: documentos descargables |
| 07/11/2026 | H6 / HU-14 | Descargas privadas de versiones y resumen con alcance autorizado; 94 pruebas, navegador aislado y revisión de PDF extensos con cien renglones | Etiqueta local h6-documentos; docs/VERIFICACION_H6.md | H7: canales confirmados y avisos |
| 08/11/2026 | H7 / HU-12 y HU-07 | Gestión de canales, tokens y avisos posteriores al commit; 131 pruebas y navegador con proveedores deshabilitados y simulación explícita de interacción | Etiqueta local h7-canales-base; docs/VERIFICACION_H7.md | Configurar proveedores y comprobar recepción real para cerrar H7 |
| 08/11/2026 | H7 / conectividad real | Configuración cargada; autenticación SMTP con TLS y bot Telegram verificado sin webhook. Diagnóstico sin envíos ni exposición de claves | docs/VERIFICACION_H7.md | Confirmar canales en Cliente y comprobar recepción de avisos |
| 08/11/2026 | H7 / confirmaciones reales | Usuario confirmó correo y Telegram; BDD corrobora una cuenta activa con ambos canales confirmados | docs/VERIFICACION_H7.md | Publicar presupuesto de prueba y comprobar ambos avisos de negocio |
| 08/11/2026 | H7 / cierre con avisos reales | Usuario confirmó recepción por correo y Telegram tras la prueba de negocio; BDD corrobora orden 1 con diagnóstico, versión 1 y estado ENTREGADO | Etiqueta local h7-avisos; docs/VERIFICACION_H7.md | H8: despliegue seguro, respaldo y restauración |

| 08/11/2026 | H8 / operación y recuperación | 131 pruebas, nueve de recuperación, cincuenta controles HTTPS y dos restauraciones con seis tablas coincidentes y margen de 68.000 conservado | Etiqueta local h8-recuperacion; docs/VERIFICACION_H8.md | H9: demo y entrega final |

## 7. Guion previsto para la demo

1. Personal inicia sesión, registra/selecciona vehículo y abre una orden.
2. Registra diagnóstico y publica presupuesto; el cliente consulta y acepta desde su cuenta.
3. Personal inicia reparación, publica un adicional y el cliente solicita revisión o lo rechaza: se muestra que la autorización anterior continúa vigente. Después se publica una versión completa corregida y se acepta.
4. Personal previsualiza y confirma el cierre; registra la entrega.
5. Administración consulta margen y período, cambia la tarifa y comprueba que el cierre histórico conserva sus valores.
6. Cliente descarga presupuesto/resumen; se muestra una denegación controlada de acceso a un recurso ajeno.
7. Se verifica un aviso real por canal confirmado y un fallo simulado que no revierte la operación.
8. Se muestran la versión ejecutada y la evidencia de restauración. El ensayo de recuperación completo puede quedar como evidencia separada para no alargar el recorrido principal.

## 8. Primer paso de implementación

H0–H8 están completos para Docker local en D:/Code/taller-ordenes. Se comprobaron avisos reales y recuperación de código y BDD. Por decisión del usuario no se registra un job diario; quedan sus instrucciones y la opción de depósito externo. HTTPS remoto está preparado y ensayado localmente. Continúa H9: demo y cierre académico.

## 9. Referencias técnicas consultadas

Consultadas el 7 de noviembre de 2026. Sirven para fijar y verificar las herramientas al implementarlas; las reglas funcionales provienen del entregable.

- [Spring Boot: requisitos de sistema y herramientas compatibles](https://docs.spring.io/spring-boot/system-requirements.html).
- [Spring Security: CSRF en aplicaciones JavaScript y SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa), incluida la renovación del token después de autenticar/cerrar sesión.
- [Flyway: soporte MySQL y módulo específico requerido](https://documentation.red-gate.com/fd/mysql-277579322.html). La compatibilidad exacta con las versiones elegidas se comprueba en H0/H1.
- [Testcontainers para Java: módulo MySQL](https://java.testcontainers.org/modules/databases/mysql/).
- [Vite: inicio y requisitos del entorno](https://vite.dev/guide/).
- [Telegram Bot API: recepción de actualizaciones](https://core.telegram.org/bots/api#getting-updates); long polling y webhook son alternativas excluyentes.
