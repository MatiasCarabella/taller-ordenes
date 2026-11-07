# Verificación de H2: acceso, sesiones y perfiles

Fecha: 7 de noviembre de 2026.

## Resultado

H2 implementado en los tres servicios Docker. El navegador abre el login en http://localhost:8080/ingresar. Las pantallas Inicio y Mi cuenta usan la sesión real; la página /estado conserva la comprobación de disponibilidad. El alcance completado es HU-01; las operaciones de vehículos y órdenes corresponden a H3.

## Pruebas automatizadas

26 pruebas ejecutadas dentro de Docker: 0 fallos, 0 errores, 0 omitidas. Registro local: verification-h2.log, excluido de Git.

- 9 pruebas de migraciones e integridad sobre MySQL real.
- 3 pruebas de política de contraseña y Argon2id.
- 3 pruebas de límite por cuenta/origen, espera, concurrencia y liberación de intentos.
- 11 pruebas HTTP de acceso sobre otro MySQL temporal: autenticación obligatoria; CSRF incluso en login; ID de sesión rotado; persistencia de identidad sin secretos en JSON; errores genéricos; los tres perfiles y acceso cruzado denegado; perfil manipulado en JSON; token anterior rechazado; logout e intento de reutilizar cookie; veinte minutos de inactividad; ocho horas pese a actividad; desactivación/cambio de perfil; reposición de contraseña; cinco fallos y recuperación tras la espera.

Las duraciones se prueban con un reloj controlado del servidor y sin esperas reales. La prueba de ocho horas mantiene actividad cada diecinueve minutos para comprobar el límite absoluto de forma independiente del timeout por inactividad.

## Navegador y proxy reales

Se comprobó React → Nginx → backend → MySQL con Administración, Personal y Cliente: login, permisos de cada área por solicitud directa, Mi cuenta, recarga y logout. Se comprobó contraseña incorrecta y se mantuvo la página de disponibilidad. No hubo errores JavaScript ni desborde horizontal en móvil.

Las cuentas ficticias estuvieron activadas solo durante el ensayo con una contraseña aleatoria efímera, no escrita a disco ni mostrada. Al terminar se restauraron sus hashes y estados originales: cinco cuentas, cero activas y cinco hashes Argon2id. Para uso manual, cada titular establece su contraseña con scripts/establecer-clave.ps1; no hay una clave de fábrica.

Las capturas de login, inicio por perfil y Mi cuenta se revisaron a 1366×900 y 390×844. La primera ejecución del ensayo detectó un desajuste de codificación del cliente SQL de pruebas para nombres acentuados; se fijó utf8mb4 y el recorrido completo pasó.

## Medición inicial de Argon2id

Medición preliminar en la misma VM Docker Desktop, sin carga representativa de producción: tres calentamientos y diez muestras. Parámetros: 19.456 KiB (19 MiB), dos iteraciones, paralelismo uno, sal de 16 bytes y salida de 32 bytes. Media de generación: 30,32 ms; media de verificación: 30,65 ms. Son valores de este entorno, no compromisos de latencia del despliegue remoto; los parámetros se revisarán en H8 con el host elegido.

## Siguiente paso

H3: alta/selección de vehículos y apertura de órdenes, con validación de cliente/titular, responsable y acceso a recursos propios en la API. Los reportes de margen, presupuestos y canales mantienen sus hitos posteriores.
