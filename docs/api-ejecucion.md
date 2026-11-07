# API de reparación, cierre y resultados

Versión implementada: H5 — 7 de noviembre de 2026.

Sesión obligatoria y CSRF en POST, según el [contrato de acceso](contrato-api.md). No hay escritura libre de estado, reapertura, ajustes de cierres ni nuevas entidades.

| Método y ruta | Perfil | Resultado |
|---|---|---|
| GET /api/ordenes/{id}/ejecucion | Todos, según titularidad | Trabajo, fechas, importe final de venta y acciones disponibles |
| POST /api/ordenes/{id}/iniciar | Personal | APROBADO → EN_REPARACION |
| POST /api/ordenes/{id}/trabajo | Personal | Guarda trabajo en EN_REPARACION |
| POST /api/ordenes/{id}/cierre/previsualizacion | Personal | Calcula valores y emite confirmación sin guardar cierre |
| POST /api/ordenes/{id}/cierre | Personal | Confirma y fija FINALIZADO |
| POST /api/ordenes/{id}/entrega | Personal | FINALIZADO → ENTREGADO; repetición devuelve la misma entrega |
| POST /api/ordenes/{id}/cancelacion | Administración | Cierra sin reparación, con motivo |
| GET /api/administracion/tarifa | Administración | Tarifa vigente, o null si no está configurada |
| POST /api/administracion/tarifa | Administración | Cambia tarifa positiva para próximos cierres |
| GET /api/administracion/resultados | Administración | Totales del período y detalle paginado por orden |

## Inicio y trabajo

Inicio recibe presupuestoAutorizadoId, un ID positivo que debe coincidir con la autorización actual: una versión ACEPTADA de la misma orden. No necesita ser la última propuesta si hay un adicional sin aceptar. Solo APROBADO permite iniciar; fecha y estado los fija el servidor.

Trabajo recibe trabajoRealizado (no vacío, máximo 4.000 caracteres), presupuestoAutorizadoId y alcanceConfirmado: true. Esta declaración expresa que el texto corresponde al alcance aceptado; la aplicación no interpreta semánticamente el texto libre. Solo se guarda en EN_REPARACION. Una nueva aceptación invalida las solicitudes que aún refieren al alcance anterior. No se registran consumos individuales ni historial de avances.

La consulta de ejecución respeta titularidad y devuelve trabajo/fechas, motivo de cancelación, ingresoFinal de venta y capacidades por perfil. No tiene campos de costos, horas, tarifa ni margen.

## Cierre

Cuerpo de previsualización:

```json
{
  "trabajoRealizado": "Reemplazo y ajuste del sistema de frenos.",
  "presupuestoAutorizadoId": 12,
  "alcanceConfirmado": true,
  "costoRepuestosTotal": "25000.00",
  "horasManoObraTotal": "2.00"
}
```

Se exige EN_REPARACION, autorización actual aceptada, descripción final y declaración de alcance. Repuestos no negativos hasta 9999999999.99 y horas no negativas hasta 999999.99, con hasta dos decimales. Cualquier propuesta PENDIENTE con ahora < venceEn bloquea el cierre, incluso una versión sustituida para nuevas respuestas. Al alcanzar el vencimiento exacto deja de bloquear.

La tarifa proviene de la única cuenta Administración y debe ser positiva. El ingreso es el total del presupuesto autorizado; no se toma un ingreso o una tarifa enviados libremente por el navegador. La previsualización devuelve esos valores, costo de mano de obra calculado, trabajo final, versión autorizada, confirmacion y venceEn. No devuelve margen a Personal.

Confirmación recibe `{"datos":{...mismo cuerpo revisado...},"confirmacion":"...token de la vista previa..."}`. El servidor revalida y recalcula después de bloquear la orden y leer la tarifa con bloqueo compartido. La confirmación está firmada, ligada a actor/orden, datos de entrada, autorización, última versión, trabajo/actualización guardados y tarifa revisada. Vence a los diez minutos. Una edición, un cambio de contexto o el reinicio del backend exige otra previsualización. No agrega almacenamiento ni tablas de borradores.

Al confirmar se copian I, CR, H y T, trabajo final y finalizado_en. Esos valores y la referencia autorizada quedan fijos. La respuesta compartida de ejecución no incluye costos internos. La vista previa permite conocerlos a Personal exclusivamente durante el cierre; el reporte de margen corresponde a Administración.

## Cálculo

Todos los resultados monetarios usan BigDecimal y dos decimales HALF_UP:

- I = total autorizado.
- CM = redondear(H × T, 2).
- M = I − CR − CM.
- M% = redondear(100 × M / I, 2), si I > 0; en otro caso null, mostrado como No aplica.

Se admite margen negativo. CM y los agregados derivados pueden exceder DECIMAL(12,2), porque no se guardan en columnas de ese tamaño. La interfaz los formatea sin convertirlos a Number para conservar centavos de importes grandes.

## Entrega y cancelación

Entrega no requiere datos de negocio en el cuerpo. Fija entregado_en sin alterar cierre ni autorización. Repetir devuelve la entrega existente, sin cambiar su fecha ni duplicar el resultado.

Cancelación recibe motivo, texto no vacío hasta 4.000 caracteres. Administración puede cancelar desde RECIBIDO, DIAGNOSTICADO, PENDIENTE_APROBACION o APROBADO. Se fija cancelado_en y no se crean datos económicos de cierre. Reparación, finalización, entrega y rechazo impiden cancelar. Todas estas acciones comparten el bloqueo de la orden con publicación/decisión.

## Tarifa y reporte

Tarifa recibe `{"tarifaHora":"6000.00"}`, positiva, hasta 9999999999.99 y dos decimales. Cambiarla no altera cierres anteriores ni la credencial de la cuenta.

Reporte exige desde/hasta como fechas ISO locales, hasta inclusiva, y admite page desde 0 y size 1–50 (20 por defecto). Rango de fechas: desde el año 1000 y hasta 9999-12-30. Fechas invertidas o inválidas reciben 400.

Se convierten las 00:00 de desde y del día posterior a hasta en Buenos Aires a UTC. Se incluyen FINALIZADO y ENTREGADO una sola vez por finalizado_en en el intervalo semiabierto; entrega no cambia el período. Los totales abarcan todo el período, aunque los items estén paginados. CM se redondea por orden antes de sumar; el porcentaje global usa sumas de I y M, sin promediar porcentajes individuales.

Los conteos separados de RECHAZADO y CANCELADO usan la fecha de rechazo (actualizado_en, fija en ese estado) y cancelado_en respectivamente, dentro del mismo intervalo. No contribuyen a ingresos, costos ni margen. La respuesta informa ambos límites UTC, totales y detalle por orden con I, CR, H, T, CM, M y M%.

## Conflictos

Además de 400 por validación, 401 por sesión, 403 por perfil/CSRF y 404 por recurso inexistente/ajeno:

| Código 409 | Motivo |
|---|---|
| ESTADO_INCOMPATIBLE | Acción no disponible en ese estado |
| AUTORIZACION_REQUERIDA | No hay aceptación válida de la misma orden |
| AUTORIZACION_CAMBIADA | Cambió la referencia aceptada |
| TARIFA_NO_CONFIGURADA | Falta tarifa positiva de Administración |
| PROPUESTA_PENDIENTE | Una propuesta pendiente aún no venció |
| VISTA_PREVIA_DESACTUALIZADA | Confirmación inválida, vencida o contexto/datos modificados |
| CANCELACION_NO_PERMITIDA | La orden ya no admite cancelación previa |
