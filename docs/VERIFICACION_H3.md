# Verificación de H3: vehículos y apertura de órdenes

Fecha: 7 de noviembre de 2026.

H3 completa HU-11 y HU-02 y, junto con HU-01, permite el primer recorrido funcional desde la interfaz: iniciar sesión como Personal, registrar o seleccionar vehículo y abrir una orden persistente. La base conserva las cinco entidades y las dos migraciones existentes.

## Implementación

- Registro con cliente activo, dominio normalizado a mayúsculas sin espacios/guiones, marca y modelo. Una coincidencia devuelve la ficha existente, con su titular y datos originales.
- Ante dos altas concurrentes, el índice único decide el registro ganador. La transacción del INSERT fallido se revierte antes de consultar esa ficha en una transacción nueva.
- Apertura en RECIBIDO, con titular derivado de la BDD, motivo y responsable Personal activo. El cliente enviado opcionalmente en JSON solo se admite como comprobación coincidente, nunca como reasignación.
- Listados paginados, filtros y fichas individuales. Administración consulta el taller; Personal consulta y opera; Cliente solo consulta sus propios registros.
- Sin edición de titular ni asignación libre de estado o valores económicos.
- La actividad válida de API mantiene actualizado el contador de inactividad de la interfaz; la autorización y la expiración siguen controladas por el servidor.

## Pruebas

Suite completa en Docker: 37 pruebas, 0 fallos, 0 errores y 0 omitidas. Registro local verification-h3.log, excluido de Git. Once pruebas nuevas verifican:

1. Normalización y ficha existente sin cambio de titular.
2. Datos inválidos, cliente inactivo, inexistente o de perfil incorrecto.
3. Dos altas concurrentes del mismo dominio: respuestas 201/200, mismo ID y una sola fila.
4. Orden RECIBIDO con titular derivado, responsable válido y ausencia de valores de cierre; dos servicios distintos reutilizan el mismo vehículo.
5. Titular manipulado, responsable inválido/inactivo, motivo vacío y vehículo inexistente, sin guardar órdenes.
6. Cliente del vehículo inactivo: apertura rechazada.
7. Listados, fichas y filtros limitados al cliente autenticado; recursos ajenos devuelven 404.
8. Consulta de Administración y escritura exclusiva de Personal; métodos de edición/eliminación denegados.
9. Catálogos de clientes y responsables activos, sin contraseñas ni perfiles incorrectos.
10. Paginación, filtros y búsquedas literales sin ampliar resultados mediante comodines o texto SQL.
11. Sesión y CSRF obligatorios.

Se conservaron las 26 pruebas anteriores de esquema, contraseñas y acceso.

## Recorrido real de navegador

Se ejecutó sobre un proyecto Compose separado, taller-ordenes-h3-qa, con MySQL, redes y volumen propios, en el puerto 8083. Usó contraseñas aleatorias efímeras y cuentas ficticias; no leyó ni modificó las contraseñas de la instancia principal.

Personal registró AB123CD, volvió a solicitar el mismo dominio con otro cliente y obtuvo la ficha original. Desde ella abrió una orden, verificó el estado Recibido y buscó el servicio. Cliente consultó su orden y recibió 404 al solicitar fichas ajenas. Administración vio las órdenes sin acciones de alta.

Después de reiniciar MySQL y backend de prueba, se volvió a ingresar y se recuperó la orden con el mismo vehículo y datos. El flujo pasó sin errores JavaScript. Se revisaron capturas de registro, detalle y listados en escritorio y de la ficha de Cliente a 390×844, sin desborde horizontal.

Al finalizar se retiraron únicamente los contenedores, redes y volumen del proyecto temporal. La instancia principal conserva sus cuentas, contraseñas y volumen.

## Uso manual

1. Establecer las claves de las cuentas Personal y Cliente que se utilizarán, mediante scripts/establecer-clave.ps1. Las cuentas recién precargadas permanecen inactivas hasta ese procedimiento.
2. Ingresar como Personal y abrir Vehículos → Registrar vehículo.
3. Elegir cliente activo, cargar dominio/marca/modelo y guardar.
4. Abrir orden desde la ficha, elegir responsable activo, ingresar motivo y confirmar.
5. Consultar la ficha y el listado. Como Cliente, verificar Mis órdenes/Mis vehículos; como Administración, consulta general.

Siguiente hito: H4, diagnóstico, presupuestos completos versionados y decisión del cliente.
