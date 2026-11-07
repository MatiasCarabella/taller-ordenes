# API de diagnóstico y presupuestos

Versión implementada: H4 — 7 de noviembre de 2026.

Todas las rutas requieren sesión; POST exige CSRF. Cliente solo consulta y responde en órdenes propias. Un presupuesto debe pertenecer a la orden indicada; los recursos inexistentes o ajenos reciben 404. Administración consulta; Personal diagnostica y publica; Cliente decide.

| Método y ruta | Perfil | Resultado |
|---|---|---|
| GET /api/ordenes/{id}/presupuestos | Todos, según titularidad | Contexto e historial completo |
| GET /api/ordenes/{id}/presupuestos/{presupuestoId} | Todos, según titularidad | Versión individual |
| POST /api/ordenes/{id}/diagnostico | Personal | 200, contexto actualizado |
| POST /api/ordenes/{id}/presupuestos/previsualizacion | Personal | 200, cálculo sin persistencia |
| POST /api/ordenes/{id}/presupuestos | Personal | 201, contexto y Location de la versión |
| POST /api/ordenes/{id}/presupuestos/{presupuestoId}/decision | Cliente titular | 200, contexto actualizado |

## Diagnóstico

Cuerpo: `{"diagnostico":"Pastillas desgastadas; reemplazar y ajustar."}`. Texto no vacío, hasta 4.000 caracteres después de quitar espacios exteriores. Se guardan autor y fecha del servidor. RECIBIDO pasa a DIAGNOSTICADO. Se puede corregir hasta la primera publicación; después queda fijo.

## Previsualización y publicación

Ambas acciones reciben el mismo cuerpo:

```json
{
  "ultimaVersionEsperada": 0,
  "presupuestoAutorizadoEsperadoId": null,
  "venceEn": "2026-11-09T18:00:00Z",
  "renglones": [
    {
      "tipo": "REPUESTO",
      "descripcion": "Kit de pastillas",
      "cantidad": "2.00",
      "precioUnitario": "1000.25"
    }
  ]
}
```

La fecha del ejemplo debe reemplazarse por un vencimiento futuro. Tipos: REPUESTO o MANO_OBRA. Entre 1 y 100 renglones completos, descripción no vacía hasta 255 caracteres, cantidad positiva hasta 999999.99 y precio no negativo hasta 9999999999.99; máximo dos decimales. Subtotal y total deben caber en DECIMAL(12,2).

El servidor redondea cada cantidad × precio a dos decimales con HALF_UP y luego suma. Un total enviado por el navegador no determina el importe. Respuesta de previsualización: venceEn, total y renglones con numero y subtotal, además de sus datos de entrada. Importes y cantidades de salida son cadenas decimales.

Cada publicación contiene todo el alcance, incluso cuando revisa o amplía otro presupuesto. El borrador vive solo en memoria del navegador. Una edición invalida la vista previa; confirmar vuelve a validar y calcular dentro de la transacción. La API de publicación también es segura sin una previsualización previa.

Se admite publicación en DIAGNOSTICADO, PENDIENTE_APROBACION, APROBADO y EN_REPARACION, con diagnóstico guardado. La primera pasa a PENDIENTE_APROBACION. Las demás conservan el estado y la autorización existente. No existen endpoints de edición o eliminación de versiones/renglones publicados.

La orden se bloquea con FOR UPDATE para generar versiones consecutivas. Se comparan ultimaVersionEsperada y presupuestoAutorizadoEsperadoId con los valores actuales; si cambió cualquiera, 409 CONTEXTO_DESACTUALIZADO obliga a actualizar y revisar la propuesta.

## Decisiones

Cuerpo: `{"decision":"ACEPTADO"}`, `{"decision":"RECHAZADO"}` o `{"decision":"REVISION"}`.

Solo el titular puede responder a la última versión pendiente, una sola vez y estrictamente antes de venceEn. La comprobación usa el reloj del servidor después de bloquear la orden; fechas almacenadas y comparaciones tienen precisión de milisegundos. Una versión anterior pendiente queda para consulta, sin posibilidad de respuesta.

| Situación | Efecto |
|---|---|
| Aceptación inicial | APROBADO y referencia a la versión aceptada |
| Rechazo inicial | RECHAZADO, sin reparación ni datos económicos de cierre |
| Revisión inicial | Conserva PENDIENTE_APROBACION; permite otra versión completa |
| Aceptación de adicional | Sustituye la autorización operativa; conserva APROBADO/EN_REPARACION |
| Rechazo o revisión de adicional | Conserva estado y autorización anterior |
| Plazo vencido | Impide responder; no agrega autorización ni elimina una aceptación anterior |

Publicación y decisión comparten el bloqueo de la orden. Ante una aceptación simultánea con otra publicación, una operación gana y la otra recibe un conflicto: no se publica sobre una autorización que cambió sin revisar su contexto. Una aceptación registrada sigue operativa después del vencimiento original, que limita la respuesta.

## Consulta

El contexto devuelve ordenId, estado, diagnostico, diagnosticoAutor, diagnosticadoEn, ultimaVersion, presupuestoAutorizadoId, puedeEditarDiagnostico, puedePublicar, ahoraEn y presupuestos en orden descendente.

Cada versión incluye id, version, autorNombre, publicadoEn, venceEn, total, decision, decisorNombre, decididoEn, ultimaVersion, autorizado, respuestaVencida, puedeResponder y renglones. Las capacidades se calculan según perfil, titularidad y estado; el servidor vuelve a verificarlas al ejecutar la acción. Última publicación y autorización operativa pueden señalar versiones diferentes. No se exponen costos, tarifa interna ni margen.

## Errores

Además de los errores de [acceso](contrato-api.md):

| HTTP | Código | Situación |
|---|---|---|
| 400 | DATOS_INVALIDOS | Campos, precisión o renglones inválidos |
| 400 | VENCIMIENTO_INVALIDO | Plazo no futuro o fuera del rango de almacenamiento |
| 400 | MONTO_FUERA_DE_RANGO | Subtotal/total desborda el importe admitido |
| 404 | RECURSO_NO_ENCONTRADO | Orden o presupuesto inexistente/ajeno |
| 409 | DIAGNOSTICO_FIJO | Ya publicado o estado incompatible |
| 409 | PUBLICACION_NO_PERMITIDA | Falta diagnóstico o estado incompatible |
| 409 | CONTEXTO_DESACTUALIZADO | Cambió la última versión o la autorización |
| 409 | DECISION_NO_PERMITIDA | Estado incompatible |
| 409 | VERSION_SUSTITUIDA | Existe una versión posterior |
| 409 | PRESUPUESTO_RESPONDIDO | Ya hubo una decisión |
| 409 | PRESUPUESTO_VENCIDO | Se alcanzó el límite de respuesta |
