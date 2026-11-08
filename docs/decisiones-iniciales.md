# Decisiones iniciales
Fecha: 7 de noviembre de 2026.

- Repositorio local: D:/Code/taller-ordenes, fuera de OneDrive. No se creó ni publicó un repositorio remoto.
- Tres servicios Compose; solo el proxy escucha en 127.0.0.1:8080. Base privada en red interna con volumen nombrado.
- Imágenes oficiales fijadas por digest; Java 25, Spring Boot 4.1.2 y Maven Wrapper. Dependencias de backend gestionadas por su BOM, salvo Bouncy Castle fijado explícitamente.
- JDBC/SQL y Flyway. V1 crea las cinco entidades; V2 agrega la FK circular a presupuesto de la misma orden.
- Una cuenta Administración como máximo mediante índice funcional único. Su existencia y activación se gestionan con la utilidad local.
- Las FK compuestas impiden titular incorrecto y autorización de otra orden. Los CHECK validan valores y coherencia local.
- H3 valida perfiles activos, titularidad y altas concurrentes mediante servicios transaccionales. H4 implementa aceptación operativa, inmutabilidad de presupuestos y transiciones relacionadas. H5 implementa cierre, entrega y resultados; las reglas entre filas se garantizan en servicios y no se consideran resueltas solo por el esquema.
- Health público sin detalles; H2 habilita los endpoints de acceso y protege las áreas por perfil. No existe una cuenta web predeterminada.
- Secretos aleatorios en archivos protegidos excluidos de Git, montados como secretos Compose; estos montajes no son un gestor externo de secretos.
- Las cuentas ficticias se crean inactivas con hashes de contraseñas aleatorias descartadas; la persona establece su contraseña en la utilidad interactiva antes de activarlas.
- H2 comprueba una huella de la credencial en cada solicitud autenticada; la reposición técnica invalida autorizaciones previas sin detener el backend. El control de identidad es un procedimiento humano, no una comprobación por conocer el correo.
- H2 implementa login, Inicio/Mi cuenta por perfil y logout. La pantalla de disponibilidad se conserva en /estado. H3 incorpora vehículos, apertura y consulta de órdenes por perfil; H4 agrega diagnóstico, presupuestos y decisiones.

- H3: registro duplicado se resuelve tras revertir el INSERT fallido y leer la ficha ganadora en otra transacción. No se sobrescribe su titular. La apertura deriva cliente y controla cuentas activas con lecturas compartidas.
- H3: Administración tiene consulta general, Personal realiza las altas y Cliente consulta únicamente registros propios. Los ensayos de navegador usan un proyecto Compose y volumen separados de la instancia del usuario.

- H4: diagnóstico, publicación y decisión se serializan mediante FOR UPDATE sobre la orden. Publicar compara última versión y autorización esperadas para detectar una aceptación concurrente incluso si no cambió el número de versión.
- H4: versiones completas e inmutables, sin borradores persistidos. Solo aceptar reemplaza la autorización; rechazar/revisar/vencer un adicional conserva la anterior.
- H4: el servidor usa BigDecimal, redondea HALF_UP cada subtotal y luego suma; rechaza precisión/desbordamiento antes de guardar. La vista previa no escribe y publicar recalcula.
- H4: el reloj se consulta después del bloqueo, con la misma precisión de milisegundos que DATETIME(3). La respuesta exige ahora < venceEn; una aceptación existente no caduca con ese plazo.

- H5: inicio, trabajo, cierre, entrega y cancelación usan el bloqueo de la orden compartido con presupuestos. Tarifa se lee con FOR SHARE; su cambio solo bloquea la fila de Administración.
- H5: confirmación de cierre firmada en memoria por diez minutos, ligada al actor, datos y contexto revisados. No agrega tablas ni borradores; se recalcula y valida al confirmar. Reiniciar exige otra vista previa.
- H5: el avance exige declaración explícita del Personal sobre el alcance autorizado y verifica su ID actual; no interpreta el texto ni incorpora detalle de consumos.
- H5: solo Administración recibe margen y configura tarifa. La consulta compartida excluye costos, horas y tarifa; Personal ve valores necesarios en la vista previa de cierre.
- H5: reporte por finalizado_en en intervalo local semiabierto, cierre y entrega sin duplicación; rechazadas por actualizado_en y canceladas por cancelado_en, en conteos separados. Porcentaje global desde totales y CM redondeado por orden antes de sumar.
- H5: los derivados monetarios grandes se conservan decimales; frontend usa BigInt para formatear la parte entera sin perder centavos. Consultar el mismo período fuerza una nueva lectura del reporte.

- H6: Apache PDFBox 3.0.8 fijado en Maven y DejaVu Sans 2.37 embebida, con licencia/huella incluidas. Generación Java en memoria sin nuevos contenedores, tablas ni archivos persistidos.
- H6: cada descarga exige sesión y propiedad de orden; presupuesto acotado a la misma orden. Se usa una proyección sin datos económicos internos y una transacción de lectura antes de renderizar.
- H6: documentos regenerados desde versión/cierre inmutables; la decisión, identificación y entrega se consultan al emitir. Resumen solo desde FINALIZADO y con la versión autorizada, aunque la última propuesta sea distinta.
- H6: descargas privadas con no-store y nombres numéricos; la interfaz usa un blob temporal y renueva el estado de actividad tras descargar. Los errores y sesiones vencidas no generan archivos falsos.
- H6: filas completas, encabezados repetidos y paginación con título junto al primer renglón y total junto al último. Caracteres sin glifo conservados mediante código Unicode explícito.
