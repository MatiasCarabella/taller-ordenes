# API de vehículos y órdenes

Versión implementada: H3 — 7 de noviembre de 2026.

Todas las rutas exigen sesión. Las operaciones POST también exigen CSRF. La pertenencia de vehículos y órdenes se valida en el servidor para cada Cliente; un recurso ajeno recibe el mismo 404 genérico que uno inexistente.

| Método y ruta | Perfiles | Resultado |
|---|---|---|
| GET /api/catalogos/clientes | Personal, Administración | Clientes activos: id, nombre y correo |
| GET /api/catalogos/personal | Personal, Administración | Responsables Personal activos: id, nombre y correo |
| GET /api/vehiculos | Todos | Página de fichas; Cliente solo ve las propias |
| GET /api/vehiculos/{id} | Todos | Ficha visible según titularidad |
| POST /api/vehiculos | Personal | 201 nuevo o 200 ficha existente; Location identifica el recurso |
| GET /api/ordenes | Todos | Página de órdenes; Cliente solo ve las propias |
| GET /api/ordenes/{id} | Todos | Orden visible según titularidad, sin costos, tarifa ni margen |
| POST /api/ordenes | Personal | 201, nueva orden RECIBIDO y Location |

## Registro de vehículo

Cuerpo: clienteId, dominio, marca y modelo. Cliente positivo, activo y de perfil CLIENTE; marca/modelo obligatorios hasta 80 caracteres después de retirar extremos vacíos. Dominio normalizado con 1–15 letras A–Z o números.

Respuesta: vehiculo y creado. La ficha contiene id, clienteId, clienteNombre, clienteActivo, dominio, marca y modelo. Un dominio repetido devuelve la ficha ya guardada; no cambia su titular, marca ni modelo, incluso si la solicitud proponía otros.

## Apertura de orden

Cuerpo: vehiculoId, responsableId y motivoIngreso (no vacío, hasta 4.000 caracteres). La persona responsable debe ser PERSONAL activo. El servidor obtiene el cliente del vehículo y exige que siga activo y sea CLIENTE.

clienteId es una comprobación opcional: si se envía otro titular, la respuesta es 409 TITULAR_NO_COINCIDE. El formulario no lo solicita como dato independiente. Estado, recibidoEn y actualizadoEn son fijados por servidor; otros valores de JSON no permiten escribirlos ni establecer importes de cierre.

Respuesta: id, vehiculoId, dominio, marca, modelo, clienteId, clienteNombre, responsableId, responsableNombre, motivoIngreso, estado, recibidoEn y actualizadoEn. Fechas ISO 8601 UTC, mostradas en Buenos Aires por la interfaz.

## Búsqueda y paginación

Ambos listados admiten q (hasta 100 caracteres), clienteId opcional, page desde 0 y size entre 1 y 50, con 20 por defecto. Respuesta: items, page, size y total. El filtro de un Cliente no puede seleccionar otro titular.

Vehículos busca dominio normalizado, marca, modelo o titular; ordena por dominio/ID. Órdenes busca dominio normalizado, cliente o motivo y admite estado y vehiculoId; ordena por ingreso/ID descendentes. Los caracteres de comodín se buscan literalmente mediante parámetros SQL escapados. Las órdenes de un vehículo ajeno al Cliente también se rechazan por titularidad.

Estados de filtro: RECIBIDO, DIAGNOSTICADO, PENDIENTE_APROBACION, APROBADO, EN_REPARACION, FINALIZADO, ENTREGADO, RECHAZADO y CANCELADO. En H3 solo se implementa la apertura RECIBIDO; las transiciones se incorporan en los hitos correspondientes.

## Errores de negocio

- 400 DATOS_INVALIDOS: campos que no cumplen las restricciones.
- 400 CLIENTE_INVALIDO / RESPONSABLE_INVALIDO: cuenta inexistente, inactiva o de perfil incorrecto.
- 400 FILTRO_INVALIDO / ESTADO_INVALIDO: filtros o parámetros inválidos.
- 403 ACCESO_DENEGADO: perfil sin permisos o filtro de otro titular.
- 404 RECURSO_NO_ENCONTRADO: ficha inexistente o ajena.
- 409 TITULAR_NO_COINCIDE: intento de abrir para otro titular.

Los errores de autenticación y CSRF conservan el [contrato de acceso](contrato-api.md). No hay endpoints de cambio de titular, modificación libre de estado ni eliminación.

Referencias de implementación: [JdbcClient y claves generadas](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.StatementSpec.html) y [lecturas con bloqueo de InnoDB](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html).
