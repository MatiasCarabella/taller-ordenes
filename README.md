# Gestión de órdenes para talleres

Aplicación web para gestionar vehículos, órdenes de reparación, presupuestos versionados y margen directo. Incluye tres roles, documentos PDF y avisos opcionales por correo y Telegram. Java/Spring Boot, React y MySQL se ejecutan con Docker.

## Ejecutar localmente (Windows / PowerShell)

Requisitos: **Git** y **Docker Desktop abierto**, con contenedores Linux y Docker Compose 2.24.4 o superior. No necesitás instalar Java, Node ni MySQL.

### 1. Clonar y preparar

```powershell
git clone https://github.com/MatiasCarabella/taller-ordenes.git
cd taller-ordenes
.\scripts\inicializar-secretos.ps1
```

### 2. Iniciar la aplicación

```powershell
docker compose up -d --build --wait
```

La primera ejecución descarga imágenes y construye las aplicaciones. Los secretos locales se conservan al repetir el paso anterior.

### 3. Preparar cuentas de prueba

En una instalación nueva, creá las cuentas ficticias:

```powershell
docker compose run --rm --no-deps backend --spring.main.web-application-type=none --taller.cuentas=demo
```

Luego ejecutá este asistente **una vez por cada cuenta que quieras activar**. Ingresá su correo y elegí una contraseña de 15 a 128 caracteres:

```powershell
.\scripts\establecer-clave.ps1
```

| Rol | Correo de prueba | Función principal |
|---|---|---|
| Administración | `admin@taller.example` | Tarifa por hora y resultados económicos |
| Personal | `personal1@taller.example` | Vehículos, órdenes, presupuestos, reparación y entrega |
| Cliente | `cliente1@taller.example` | Consultar sus órdenes y responder presupuestos |

También se crean `personal2@taller.example` y `cliente2@taller.example`. **No hay contraseñas predeterminadas:** las cuentas quedan inactivas hasta establecer su clave. Las cuentas existentes se conservan. Los vehículos y órdenes se cargan desde la aplicación.

### 4. Ingresar

Abrí **[http://localhost:8080/ingresar](http://localhost:8080/ingresar)** con una de las cuentas activadas. Para recorrer el sistema, activá al menos una cuenta de cada rol. Podés consultar la disponibilidad en [http://localhost:8080/estado](http://localhost:8080/estado).

## Detener y volver a iniciar

Desde la carpeta del repositorio:

```powershell
docker compose stop
docker compose up -d --wait
```

Los datos se conservan en el volumen MySQL. **`docker compose down -v` elimina ese volumen y sus datos.**

## Documentación y pruebas

- [Operación, HTTPS, respaldos manuales y recuperación](docs/OPERACION_Y_RECUPERACION.md).
- [Configurar correo y Telegram](docs/CONFIGURAR_CANALES.md). Son opcionales; la instalación nueva funciona con los canales deshabilitados.
- [API y controles de acceso](docs/contrato-api.md).
- [Verificación de seguridad y recuperación](docs/VERIFICACION_H8.md).

Para construir y ejecutar las pruebas en Docker:

```powershell
.\scripts\verificar.ps1
```
