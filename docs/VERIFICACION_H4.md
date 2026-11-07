# Verificación de H4: diagnóstico, presupuestos y decisiones

Fecha: 7 de noviembre de 2026.

H4 completa HU-03, HU-04 y HU-05. Conserva las cinco entidades y las dos migraciones; agrega servicios transaccionales, API y pantallas reales sobre la base existente.

## Implementación

- Diagnóstico con autor/fecha, corregible antes de publicar y fijo después.
- Editor de propuestas completas; borrador solo en memoria, vista previa calculada por servidor y confirmación de publicación.
- Cantidades/precios decimales, HALF_UP por renglón antes de sumar, límites de precisión y desbordamiento.
- Versiones correlativas, contenido publicado inmutable y fechas UTC con precisión de milisegundos; entrada y presentación de vencimientos en Buenos Aires.
- Consulta histórica que distingue última propuesta y autorización operativa.
- Aceptación, rechazo y revisión por el titular, una sola vez, sobre la última versión pendiente y antes de vencer.
- Rechazo inicial sin reparación/cierre económico; rechazo, revisión o vencimiento de adicional conservan la autorización previa.
- Bloqueo de orden compartido entre diagnóstico, publicación y decisión. Publicar exige que coincidan la última versión y la autorización que se revisaron.
- Administración consulta, Personal diagnostica/publica y Cliente decide solo en órdenes propias.

## Pruebas automáticas

Ejecutado scripts/verificar.ps1 en Docker: **55 pruebas, 0 fallos, 0 errores, 0 omitidas**. Registro local verification-h4.log, excluido de Git. Se conservaron las 37 anteriores y se agregaron 18 pruebas HTTP/integración con MySQL real:

1. Corrección y congelamiento de diagnóstico.
2. Diagnóstico obligatorio y vista previa sin escrituras.
3. Redondeo HALF_UP por renglón y total calculado sin confiar en el cliente.
4. Renglones inválidos, nulos, precisión, desbordamiento y ausencia de escrituras parciales.
5. Vencimiento futuro, precisión de milisegundos y límite superior de fecha.
6. Versiones correlativas y respuesta denegada a una versión sustituida.
7. Aceptación inicial con titular/fecha y respuesta única.
8. Rechazo inicial sin datos económicos y bloqueo de publicación posterior.
9. Revisión inicial y nueva versión completa.
10. Aceptación operativa después del plazo; rechazo/revisión de adicionales y posterior aceptación.
11. Adicionales sin degradar EN_REPARACION.
12. Vencimiento exacto excluido y adicional vencido sin nueva autorización.
13. Perfiles, titularidad y presupuesto de otra orden.
14. Ausencia de rutas de edición, contenido sin cambios y CSRF.
15. Orden cancelada sin diagnóstico ni publicación.
16. Dos publicaciones concurrentes: una 201 y una 409; una versión persistida.
17. Dos decisiones concurrentes: una 200 y una 409; una respuesta persistida.
18. Publicación simultánea con aceptación: un ganador y un conflicto, sin contexto obsoleto aceptado.

Las carreras y decisiones se prueban con transacciones sobre InnoDB; los límites temporales usan un Clock inyectable.

## Recorrido de navegador

Chrome real automatizado sobre taller-ordenes-h4-qa, puerto 8084, con MySQL, redes y volumen separados. Contraseñas aleatorias efímeras; no se leyeron ni cambiaron las del usuario.

Personal registró vehículo/orden, guardó y corrigió diagnóstico, previsualizó ARS 2.000,50 y publicó la versión 1. Se comprobó la conversión de vencimiento Buenos Aires/UTC y que el diagnóstico quedara fijo. Cliente solicitó revisión. Personal preparó una versión 2 completa de ARS 5.500,50; editar un precio invalidó la vista previa, que debió recalcular antes de publicar. Cliente aceptó la versión 2.

Personal publicó una versión 3 de ARS 6.500,00. Cliente la rechazó; la orden conservó APROBADO y la autorización de la versión 2. El historial mantuvo las tres versiones y sus decisiones. Otro cliente recibió 404; Administración consultó sin acciones de publicación.

Se reiniciaron MySQL y backend de prueba, se volvió a ingresar y se recuperaron estado, versiones y autorización. No se detectaron errores JavaScript. Se inspeccionaron capturas de editor/vista previa, historial de Administración y Cliente a 390×844. La página móvil no desborda; las tablas permiten desplazamiento horizontal dentro de su contenedor.

Se retiraron únicamente contenedores, redes y volumen de taller-ordenes-h4-qa.

## Uso manual

1. Personal → Órdenes → detalle → guardar diagnóstico.
2. Preparar presupuesto → cargar alcance completo y plazo → Revisar presupuesto → Confirmar publicación.
3. Cliente titular → Mis órdenes → detalle → aceptar, rechazar o solicitar revisión; confirmar la decisión.
4. Personal → Actualizar orden → preparar otra versión completa si corresponde.
5. Comparar la última propuesta con el bloque Alcance autorizado.

H5 continúa con inicio de reparación, trabajo, cierre, entrega y margen. Los estados EN_REPARACION y CANCELADO usados en pruebas de H4 se prepararon como fixtures válidos; las acciones para iniciarlos desde la aplicación corresponden a H5.

## Instancia local y versión

Desplegado con docker compose up -d --wait. Backend, frontend y MySQL saludables; /api/health informa UP y el frontend servido contiene H4. Se conservó taller-ordenes_mysql_data; el contenedor MySQL original no fue recreado. No se ejecutó precarga ni reposición de claves en esta instancia. El proyecto y volumen temporales de QA fueron retirados.

Punto de recuperación: etiqueta Git local h4-presupuestos. Ambas copias de PLAN_IMPLEMENTACION.md tienen contenido idéntico.
