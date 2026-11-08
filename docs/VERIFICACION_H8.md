# Verificación de H8: operación y recuperación

Fecha: 8 de noviembre de 2026. Alcance acordado: Docker local, configuración HTTPS remota preparada y ensayada localmente, respaldos manuales. Por pedido del usuario no se registra el job diario; su instalación queda documentada. No se configura un depósito externo ni se afirma sincronización cloud comprobada.

## Construcción y despliegue

Frontend y backend construidos desde Dockerfiles con imágenes base fijadas por digest; dependencias frontend con lockfile y backend mediante Maven Wrapper/BOM. El contenedor de herramientas usa Docker CLI 28.5.2, age 1.2.1, Python 3.12.16 y Git 2.49.1, con versión explícita de paquetes directos. Las instrucciones operativas están en [OPERACION_Y_RECUPERACION.md](OPERACION_Y_RECUPERACION.md).

La instancia principal conserva MySQL creado el 7 de noviembre de 2026 y el volumen taller-ordenes_mysql_data. La actualización recreó solamente backend y frontend; los tres servicios permanecen saludables y /api/health devuelve UP. MySQL y backend no publican puertos al host. No se cambiaron contraseñas ni se ejecutó precarga en esa instancia.

El arranque desde cero detectó dos problemas y permitió corregirlos: permisos del código clonado bajo umask privada, y opciones `set -u` del inicializador que se propagaban al entrypoint MySQL al cargarlo con source. El código público se clona con permisos de lectura normales; el inicializador aísla sus opciones y variables en un subshell. El ensayo corregido tuvo cero reinicios MySQL. Los temporales descifrados y secretos mantienen permisos privados. No se agregaron privilegios globales al usuario de respaldo.

## Seguridad y pruebas

- Suite completa de backend en Docker/MySQL temporal: 131 pruebas, cero fallos, errores u omisiones.
- Nueve pruebas de herramientas: alteración de checksum, rutas inesperadas, promoción únicamente de copias válidas, detección de copia existente corrupta, retención diaria/semanal/mensual, rechazo de enlaces fuera del depósito, rechazo de proyecto principal sin ejecutar SQL y de BDD de recuperación no vacía. Los dos rechazos se comprueban con dobles, sin apuntar una importación a la base principal.
- Sintaxis PowerShell y shell, Compose normal y superposición TLS comprobados.
- Cincuenta comprobaciones HTTP sobre el entorno recuperado con TLS real de laboratorio: certificado temporal confiado explícitamente con SAN localhost/127.0.0.1, CSP/HSTS, cookie Secure/HttpOnly/SameSite=Lax, sesión, matriz de los tres perfiles, un Cliente ajeno, catálogos, tarifa, canales, órdenes, ejecución y PDF; CSRF y redirección HTTP 308.
- Ráfaga de solicitudes que falsifican X-Forwarded-For: respuesta 429. El proxy reemplaza esas cabeceras y limita por el origen recibido directamente.
- Un marcador en la query no aparece en los registros del proxy. El formato técnico conserva método, estado y duración; omite datos de solicitud.

La matriz completa de reglas y concurrencia sigue cubierta por las 131 pruebas; las cincuenta comprobaciones adicionales verifican los controles críticos atravesando el proxy HTTPS. No se afirma despliegue remoto: no se proporcionó servidor ni dominio. El certificado del ensayo es autofirmado y no se usa para exponer el portal a terceros.

## Respaldo y recuperación independientes

Se generaron copias locales cifradas en D:/Backups/taller-ordenes con cuenta taller_backup, snapshot InnoDB, gzip, SHA-256 y age. Se descifraron y validaron antes de promoverlas. La coordinación de actualización/migración exige usar scripts/actualizar.ps1: comparte bloqueo de archivo con scripts/respaldar.ps1; no ejecutar DDL o Flyway manual durante una copia.

El paquete de código contiene bundle Git autónomo, archivo de la versión exacta y manifiesto. La recuperación clona el bundle sin montar el repositorio original, selecciona el commit, crea secretos MySQL nuevos, importa una BDD vacía y compara las seis tablas, incluido el historial Flyway. Las cuentas conservan los hashes importados; los canales de proveedores permanecen deshabilitados en el ensayo. No se consultaron ni registraron contraseñas elegidas por el usuario.

Ensayos medidos con código df5c293 y respaldos asociados:

| Ensayo | Antigüedad del respaldo al importar | Duración hasta servicios saludables | Resultado |
|---|---:|---:|---|
| Recuperación de la instalación | 88,118 s | 70,845 s | Seis tablas coincidentes; arranque desde cero |
| Recuperación de caso ficticio conocido | 36,234 s | 67,408 s | Seis tablas coincidentes; autorización y margen conservados |

La versión final etiquetada h8-recuperacion (8d9ef90) se volvió a respaldar y recuperar desde cero: servicios saludables en 59,126 s. Se comprobó que su archivo .env conserva proyecto y puerto aislados sin depender de variables externas; /api/health respondió UP.

El caso conocido se creó únicamente en un entorno recuperado aislado y se volvió a respaldar/restaurar en otro proyecto independiente: ingreso 100.000, repuestos 20.000, dos horas a tarifa histórica 6.000, margen directo 68.000. La consulta después de la segunda restauración comprobó presupuesto autorizado y aceptado, y todos esos valores. No es un dato real de negocio.

Las duraciones del ensayo están por debajo del objetivo RTO 4 h y la antigüedad de los respaldos recuperados por debajo del objetivo RPO 24 h. Son mediciones locales con datos pequeños y dependencias Docker disponibles; no prueban garantía ante incidentes ni cumplimiento continuo del RPO. La copia es manual por decisión del usuario.

La retención 7 diarias/4 semanales y 3 mensuales del destino externo opcional está implementada y cubierta por pruebas. Las copias semanales locales se generan con la primera copia válida del período; la rotación ocurre después de verificar nuevos destinos. Las instrucciones de tarea diaria a las 02:00 y depósito privado externo están disponibles, sin activarlos. La identidad age queda en .secrets/respaldo-age.key, fuera del depósito y Git; su custodia separada corresponde al titular.

## Versionado y publicación

Puntos de recuperación: h8-operacion-base para la implementación inicial, y h8-recuperacion para el cierre con evidencia. Repositorio público creado: [MatiasCarabella/taller-ordenes](https://github.com/MatiasCarabella/taller-ordenes). Contiene código y documentación. Antes de publicarlo se revisan todos los blobs de su historial contra credenciales locales y patrones de claves privadas, y se comprueba que no existan archivos de secretos o respaldos versionados. La auditoría informa solo cantidades y hallazgos, nunca valores secretos.

Los registros detallados de ejecución están en verification-h8-*.log, excluidos de Git. Los proyectos temporales de recuperación se retiran al concluir las comprobaciones; la instancia principal sigue disponible en http://localhost:8080.
