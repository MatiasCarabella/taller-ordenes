# Verificación de H6: presupuestos y resumen descargables

Fecha: 7 de noviembre de 2026.

H6 completa HU-14. Agrega dos descargas PDF a la API y a las pantallas existentes, sin modificar las cinco entidades ni las dos migraciones.

## Controles

- Sesión y titularidad verificadas en cada solicitud; presupuesto acotado a la orden de la ruta.
- Cliente solo descarga documentos propios; Personal y Administración consultan documentos del taller.
- Proyección SQL sin costos, horas, tarifa, margen, credenciales ni tokens; mismo documento de venta para los tres perfiles.
- Presupuesto con la versión solicitada; resumen solo desde FINALIZADO/ENTREGADO y con la versión autorizada al cierre.
- Datos persistidos leídos con consistencia transaccional, sin depender del navegador. Generación posterior en memoria.
- Content-Type/Content-Disposition correctos, nombre numérico controlado y no-store. Errores JSON manejados sin producir un archivo falso.
- A4, fuente embebida, acentos, fechas de Buenos Aires, importes decimales, tablas continuadas y numeración.

## Pruebas automáticas

Suite completa en Docker y MySQL temporal: 94 pruebas, 0 fallos, 0 errores y 0 omitidas. Catorce nuevas pruebas de integración verifican:

1. PDF válido, adjunto, nombre, longitud, no-store, nosniff, texto legible y fuente embebida; sin acciones activas ni adjuntos.
2. Los tres perfiles descargan sin información económica interna.
3. Otra titularidad, orden inexistente y presupuesto de otra orden devuelven 404.
4. Sesión obligatoria, métodos de escritura denegados y errores Problem Details.
5. Resumen no disponible en los estados previos a finalización.
6. Resumen con trabajo persistido, aceptación e importe final de venta.
7. Adicional rechazado sin reemplazar la versión del resumen.
8. Entrega y cambio de tarifa sin alterar los valores históricos.
9. Versiones anteriores conservadas después de nueva aceptación y cierre.
10. Rechazo/cancelación sin resumen; un presupuesto publicado sigue descargable.
11. Cien renglones extensos, acentos, subtotales/total, encabezados y numeración; posiciones de todo el texto dentro de página. Título unido al inicio de tabla y total al último renglón.
12. Unicode sin glifo y caracteres de control sin romper la generación.
13. Recurso ajeno recibe 404 antes de revelar si tiene resumen.
14. Descargas simultáneas sin mezclar documentos.

Registro local verification-h6.log, excluido de Git. Se conservaron las 80 pruebas anteriores.

## Navegador

Chrome sobre taller-ordenes-h6-qa, puerto 8086, con MySQL, red y volumen independientes y cuentas ficticias con claves efímeras.

Cliente descargó desde la interfaz el presupuesto v1 aceptado de ARS 70.000, el presupuesto v2 rechazado de ARS 80.000 y el resumen final correspondiente a v1. Antes del cierre no apareció el botón de resumen y la API respondió 409. Personal y Administración descargaron; otro Cliente recibió 404 en ambas rutas.

Se descargaron también presupuesto y resumen con cien renglones y diagnóstico/trabajo de 4.000 caracteres. Se verificó el nombre de archivo y la descarga real del navegador. La vista móvil mantuvo los botones dentro del ancho. Después de invalidar la sesión en el servidor, intentar descargar llevó al login con aviso, sin archivo de error. No hubo errores JavaScript.

Se retiraron únicamente contenedores, redes y volumen del proyecto temporal.

## Revisión visual final

Se reabrieron los cinco PDF descargados con pypdf para verificar texto, numeración y ausencia de datos internos. Se renderizaron con Poppler y se inspeccionaron documentos cortos y todas las páginas de los casos extensos mediante vistas de contacto, con revisión ampliada del inicio del detalle y páginas finales. Se comprobó que no hubiera cortes, solapamientos ni glifos ilegibles.

Después del ajuste de paginación, el título acompaña al primer renglón y el total al último. La columna numérica conserva completo el renglón 100. Las catorce pruebas de documentos se repitieron sin fallos tras ese último ajuste de ancho (verification-h6-pdf-final.log); se generaron y renderizaron además ejemplos ficticios con las clases finales del backend: presupuesto de 28 páginas y resumen de 29. Ambos conservan los cien renglones y el total en la última página.

## Uso manual

1. Abrir una orden; desplegar la versión deseada en Presupuestos y pulsar Descargar presupuesto vN (PDF).
2. Desde FINALIZADO o ENTREGADO, ir a Trabajo y entrega → Descargar resumen final (PDF).
3. El resumen toma el alcance aceptado, aunque la última propuesta publicada sea otra.

Siguiente hito: H7, confirmación de correo/Telegram y avisos por canales confirmados.

## Despliegue y versión

Desplegado con docker compose up -d --wait. Los tres servicios están saludables, /api/health informa UP y el frontend servido incluye ambas descargas. Se conservaron el contenedor MySQL original y taller-ordenes_mysql_data; no se ejecutó precarga ni reposición de contraseñas en la instancia del usuario. El proyecto y volumen temporales de taller-ordenes-h6-qa se retiraron.

Punto de recuperación: etiqueta Git local h6-documentos. Ambas copias del plan tienen contenido idéntico.
