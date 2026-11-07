# Verificación de la base implementada

Fecha: 7 de noviembre de 2026.

## Resultado

H0 y H1 implementados y verificados en Docker Desktop, motor Linux 28.1.1, Compose 2.35.1. La aplicación se encuentra disponible en http://localhost:8080 para comprobar conectividad; las historias de acceso y gestión aún no están implementadas.

| Comprobación | Evidencia |
|---|---|
| Construcción Docker de backend | Java 25, Spring Boot 4.1.2 y Maven Wrapper: BUILD SUCCESS |
| Construcción Docker de frontend | React 19.3.1, TypeScript 7.0.2, Vite 8.3.6 y Node 24.22.2: compilación correcta |
| Arranque Compose | frontend, backend y db en estado healthy |
| BDD | MySQL 8.4.13, volumen persistente y dos migraciones Flyway aplicadas |
| Entidades | Cinco tablas de dominio y una tabla técnica de Flyway |
| Pruebas automatizadas | 12 ejecutadas, 0 fallos, 0 errores, 0 omitidas |
| Integridad | Nueve pruebas de esquema/migración sobre MySQL temporal real |
| Contraseñas | Tres pruebas de política, Unicode, hash Argon2id, sal y verificación completa |
| Datos ficticios | Una Administración, dos Personal y dos Clientes; cinco hashes Argon2id; cero cuentas activas |
| Precarga repetida | Conserva las mismas cinco cuentas; no modifica credenciales existentes |
| Persistencia | Reinicio de MySQL/backend y arranque posterior healthy; cuentas y migraciones conservadas |
| Proxy y API | GET /api/health: 200, UP; GET /api/ordenes: 403 con configuración inicial |
| Navegador | Estado «Servicio disponible» desde React, sin errores JavaScript |
| Adaptación | Capturas revisadas a 1280×800 y 390×844; sin desborde horizontal |
| Privilegios de contenedor | Backend UID 10001 y frontend UID 101; ambos sin root |
| Puertos | Solo 127.0.0.1:8080 publicado; sin puertos de MySQL/backend en el host |

## Cómo repetir

Desde la raíz: ejecutar `./scripts/verificar.ps1`, y luego `docker compose up -d --wait`. La precarga se ejecuta con el comando indicado en README. Los registros locales de esta ejecución están en `verification.log`, `cuentas.log` y `cuentas-repeat.log`, excluidos de Git.

La comprobación visual fue una verificación de la pantalla de disponibilidad, no de los recorridos funcionales futuros. La utilidad de cuentas se verificó mediante precarga idempotente y pruebas de contraseña; el establecimiento asistido de una clave individual se realizará al preparar las cuentas para el login.

## Próximo incremento

H2: autenticación con las cuentas de BDD, sesión, CSRF, límites de intentos, autorización y primera navegación por perfil. Después, H3 completa vehículo y apertura de orden. Las reglas transaccionales de presupuestos/cierre y las integraciones permanecen en sus hitos previstos.
