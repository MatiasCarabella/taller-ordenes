# API de documentos descargables

Versión implementada: H6 — 7 de noviembre de 2026.

| Método y ruta | Perfiles | Resultado |
|---|---|---|
| GET /api/ordenes/{id}/presupuestos/{presupuestoId}/pdf | Personal, Administración y Cliente titular | PDF de una versión publicada |
| GET /api/ordenes/{id}/resumen/pdf | Personal, Administración y Cliente titular | PDF de resumen desde FINALIZADO o ENTREGADO |

Ambas rutas requieren sesión. Cliente solo accede a sus órdenes. El presupuesto debe pertenecer a la orden de la ruta, también para perfiles del taller. Una orden inexistente o ajena recibe el mismo 404; se verifica antes de informar si tiene resumen. No hay enlaces públicos ni tokens de descarga. GET no modifica el negocio.

## Respuesta

Content-Type: application/pdf; Content-Disposition: attachment; Content-Length explícito y Cache-Control: no-store. Nombres generados solo con ID y versión:

- presupuesto-orden-{id}-v{version}.pdf
- resumen-orden-{id}.pdf

Los errores conservan Problem Details JSON y su estado HTTP; no se descargan como PDF. La interfaz maneja sesión vencida y muestra errores de descarga. Los archivos se reciben por fetch autenticado y una URL blob temporal, revocada después de iniciar la descarga.

## Contenido

El presupuesto identifica orden, cliente, vehículo, responsable, versión, autor/fecha de publicación, plazo, decisión/decisor/fecha cuando existen y autorización operativa al emitir. Incluye motivo, diagnóstico, todos los renglones publicados y total de esa versión. Se puede descargar cualquier versión publicada, aunque esté sustituida, vencida, revisada o rechazada.

El resumen identifica trabajo final, fechas del servicio y entrega si ya ocurrió, versión autorizada y su aceptación, renglones de venta de esa versión e ingreso final copiado en el cierre. Una propuesta posterior rechazada no reemplaza el alcance del resumen.

Los valores de publicación y cierre provienen de la BDD. Se leen mediante una transacción consistente y una proyección que excluye costos, horas, tarifa, margen, credenciales y tokens. La generación ocurre después de obtener esa proyección, sin mantener la conexión durante la composición del PDF. No depende del DOM ni acepta importes enviados para descargar.

Los PDF se regeneran al solicitarse: la decisión, autorización operativa, identificación de personas y estado/entrega se consultan al emitir, con fecha de generación. El contenido versionado, trabajo final e importes del cierre conservan sus valores históricos. No se agrega almacenamiento de archivos ni historial de emisiones.

## Formato

A4, tipografía embebida, fechas de Buenos Aires e importes de venta en ARS. Tablas con encabezados repetidos, descripciones ajustadas al ancho, filas completas, títulos unidos al inicio del detalle y total junto al último renglón. Encabezado de orden/versión y numeración en cada página.

Se preservan acentos, ñ y signos del español. Un carácter sin glifo se representa explícitamente como [U+...] y se informa en el documento; no se elimina silenciosamente ni impide descargar el resto. No se incorporan HTML, scripts, enlaces activos, formularios o adjuntos desde campos de texto.

## Errores

- 401: sesión inexistente, inválida o vencida.
- 403: método/perfil sin autorización.
- 404 RECURSO_NO_ENCONTRADO: orden o presupuesto inexistente/ajeno, o presupuesto de otra orden.
- 409 RESUMEN_NO_DISPONIBLE: servicio anterior a finalización, rechazado o cancelado.
- 503 PDF_NO_DISPONIBLE: no se pudo generar el documento.

Ver [dependencias y licencias PDF](dependencias-pdf.md) y [verificación de H6](VERIFICACION_H6.md).
