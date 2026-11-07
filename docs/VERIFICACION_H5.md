# Verificación de H5: reparación, cierre, entrega y resultados

Fecha: 7 de noviembre de 2026.

Implementa HU-06, HU-08, HU-09, HU-10, HU-13 y HU-15 sobre las cinco entidades y las dos migraciones existentes.

## Controles implementados

- Inicio APROBADO → EN_REPARACION con aceptación de la misma orden.
- Trabajo con declaración explícita de correspondencia con el alcance actual; autorización antigua rechazada si cambia la aceptación.
- Cierre con vista previa sin persistencia y confirmación ligada a los datos y contexto revisados, válida diez minutos.
- Bloqueo de orden compartido con publicación, decisión, cancelación y entrega; lectura compartida de tarifa al previsualizar/confirmar.
- Bloqueo por cualquier presupuesto pendiente no vencido. El vencimiento exacto deja de impedir cerrar; no autoriza trabajo adicional.
- Copia inmutable de ingreso autorizado, tarifa vigente, repuestos, horas, trabajo final y fecha de finalización.
- Cálculo decimal HALF_UP, margen negativo y porcentaje No aplica si ingreso cero; agregación desde totales y por fecha local de finalización.
- Entrega idempotente sin alterar cierre. Cancelación exclusiva de Administración antes de reparar, con motivo y sin datos económicos.
- Cliente consulta su trabajo/entrega e importe de venta sin costos, horas, tarifa ni margen. Personal previsualiza valores de cierre; solo Administración consulta resultados y configura tarifa.

## Suite automática

Ejecutado scripts/verificar.ps1 en Docker con MySQL real temporal: 80 pruebas, 0 fallos, 0 errores y 0 omitidas. Se conservan las 55 anteriores y se agregan 25 pruebas HTTP/integración de H5:

1. Inicio solo aprobado y con autorización actual, aunque exista adicional sin aceptar.
2. Trabajo con declaración, alcance actual y consulta por titular.
3. Vista previa calculada sin cierre persistido ni margen para Personal.
4. Caso I=70.000, CR=25.000, H=2 y T=6.000: CM=12.000, M=33.000 y 47,14 %.
5. Ingreso cero, porcentaje nulo y margen negativo.
6. Precisión, no negativos, declaración y descripción obligatorias; costos cero.
7. HALF_UP y cantidades derivadas grandes sin pérdida decimal.
8. Tarifa positiva exclusiva de Administración y copia histórica inalterada.
9. Tarifa ausente bloquea previsualización y cierre.
10. Cambio de tarifa invalida vista previa.
11. Cambio de datos o trabajo guardado invalida vista previa.
12. Cambio de autorización o nueva versión publicada invalida vista previa.
13. Confirmación inválida, de otro actor o en el vencimiento exacto.
14. Presupuestos pendientes vigentes, incluidos sustituidos, bloquean; vencimiento exacto permite cierre.
15. Trabajo y cierre inmutables; entrega repetida conserva fecha y no duplica resultados.
16. Entrega anterior al cierre rechazada.
17. Cancelación desde los cuatro estados previos, sin valores económicos.
18. Cancelación con motivo, perfil correcto y prohibida tras inicio/rechazo.
19. Dos cierres concurrentes: una confirmación y un conflicto.
20. Publicación y cierre simultáneos: solo uno gana.
21. Cambio de tarifa simultáneo: copia consistente de la tarifa revisada o conflicto.
22. Límites semiabiertos Buenos Aires/UTC, porcentaje global desde totales y entrega sin cambiar período.
23. Validación de filtros/paginación y acceso exclusivo de Administración.
24. Consulta pública del trabajo sin datos internos, titularidad, perfiles y CSRF.
25. Inicio y cancelación simultáneos: solo una transición gana.

Registro local verification-h5.log, excluido de Git. Los ensayos temporales usan Clock inyectable y bases separadas de la aplicación.

## Navegador y uso

El recorrido se ensaya en Chrome sobre taller-ordenes-h5-qa, puerto 8085, con base/volumen/redes propios y claves efímeras. Las credenciales y datos de la instancia del usuario quedan fuera del ensayo.

En el ensayo, Personal inició reparación, guardó trabajo y revisó el cierre. Un adicional pendiente lo bloqueó; luego de rechazarlo, la vista previa calculó I=70.000, CR=25.000 y CM=12.000. Editar horas retiró la confirmación. Administración cambió la tarifa entre revisión y confirmación: el servidor respondió 409 y exigió otra vista previa. Con tarifa 6.000 se confirmó el cierre y la entrega.

Administración cambió después la tarifa a 8.000; el detalle conservó T=6.000, M=33.000 y 47,14 %. Consultar nuevamente el mismo período refrescó los datos. Se canceló otra orden desde la interfaz y se contó separadamente. Cliente vio trabajo/entrega e importe final, sin datos internos; las peticiones directas a resultados/tarifa se rechazaron según perfil.

Se reiniciaron MySQL y backend, se volvió a ingresar y se recuperaron cierre y entrega con las mismas fechas. No hubo errores JavaScript. Se inspeccionaron capturas de vista previa, resultados de escritorio y móvil, y entrega del cliente a 390×844, sin desborde de página. Se retiraron únicamente contenedores, redes y volumen de taller-ordenes-h5-qa.

Recorrido manual disponible:

1. Personal abre una orden aceptada, confirma Iniciar reparación, carga trabajo y declara su correspondencia con la versión autorizada.
2. Preparar cierre → repuestos/horas → Revisar cierre. Resolver o dejar vencer propuestas pendientes antes de continuar.
3. Revisar importes, alcance, trabajo y tarifa; Confirmar finalización. Después solo se permite Registrar entrega.
4. Administración → Resultados y tarifa: consultar período, desplegar detalle por orden y actualizar tarifa para próximos cierres.
5. Administración puede cancelar una orden anterior a reparación, con motivo.
6. Cliente consulta trabajo, finalización y entrega desde su orden.

Siguiente hito: H6, descargas de presupuestos y resumen final.

## Despliegue y versión

Desplegado con docker compose up -d --wait. Los tres servicios están saludables, /api/health informa UP y el frontend servido contiene H5. Se conservó el contenedor MySQL original y taller-ordenes_mysql_data; no se ejecutaron precarga ni reposición de contraseñas en la instancia del usuario. Backend reiniciado: requiere volver a ingresar. El entorno y volumen de taller-ordenes-h5-qa fueron eliminados.

Punto de recuperación: etiqueta Git local h5-cierre-resultados. Ambas copias del plan tienen contenido idéntico.
