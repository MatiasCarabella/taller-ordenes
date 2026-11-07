CREATE TABLE usuario (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    perfil VARCHAR(20) NOT NULL,
    nombre VARCHAR(255) NOT NULL,
    correo VARCHAR(255) NOT NULL,
    clave_hash VARCHAR(255) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT FALSE,
    correo_confirmado BOOLEAN NOT NULL DEFAULT FALSE,
    chat_telegram VARCHAR(32) NULL,
    token_canal_hash VARCHAR(255) NULL,
    token_canal_tipo VARCHAR(10) NULL,
    token_canal_vence DATETIME(3) NULL,
    tarifa_hora_vigente DECIMAL(12,2) NULL,
    CONSTRAINT uq_usuario_correo UNIQUE (correo),
    CONSTRAINT uq_usuario_chat UNIQUE (chat_telegram),
    CONSTRAINT ck_usuario_perfil CHECK (perfil IN ('ADMINISTRACION','PERSONAL','CLIENTE')),
    CONSTRAINT ck_usuario_nombre CHECK (CHAR_LENGTH(TRIM(nombre)) > 0),
    CONSTRAINT ck_usuario_correo CHECK (CHAR_LENGTH(TRIM(correo)) > 0),
    CONSTRAINT ck_usuario_clave CHECK (CHAR_LENGTH(clave_hash) > 0),
    CONSTRAINT ck_usuario_booleanos CHECK (activo IN (0,1) AND correo_confirmado IN (0,1)),
    CONSTRAINT ck_usuario_tarifa CHECK (
      (perfil = 'ADMINISTRACION' AND (tarifa_hora_vigente IS NULL OR tarifa_hora_vigente > 0))
      OR (perfil <> 'ADMINISTRACION' AND tarifa_hora_vigente IS NULL)),
    CONSTRAINT ck_usuario_token CHECK (
      (token_canal_hash IS NULL AND token_canal_tipo IS NULL AND token_canal_vence IS NULL)
      OR (token_canal_hash IS NOT NULL AND token_canal_tipo IS NOT NULL
          AND token_canal_tipo IN ('CORREO','TELEGRAM') AND token_canal_vence IS NOT NULL))
) ENGINE=InnoDB;
-- Functional uniqueness allows multiple clients/staff and at most one administrator.
CREATE UNIQUE INDEX uq_usuario_unica_administracion
    ON usuario ((IF(perfil = 'ADMINISTRACION', 1, NULL)));

CREATE TABLE vehiculo (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cliente_id BIGINT UNSIGNED NOT NULL,
    dominio VARCHAR(15) NOT NULL,
    marca VARCHAR(80) NOT NULL,
    modelo VARCHAR(80) NOT NULL,
    CONSTRAINT uq_vehiculo_dominio UNIQUE (dominio),
    CONSTRAINT uq_vehiculo_titular UNIQUE (id, cliente_id),
    CONSTRAINT fk_vehiculo_cliente FOREIGN KEY (cliente_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT ck_vehiculo_datos CHECK (
      CHAR_LENGTH(TRIM(dominio)) > 0 AND CHAR_LENGTH(TRIM(marca)) > 0
      AND CHAR_LENGTH(TRIM(modelo)) > 0),
    CONSTRAINT ck_vehiculo_dominio CHECK (
      dominio COLLATE utf8mb4_0900_as_cs = UPPER(REPLACE(REPLACE(dominio, ' ', ''), '-', '')) COLLATE utf8mb4_0900_as_cs)
) ENGINE=InnoDB;

CREATE TABLE orden (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cliente_id BIGINT UNSIGNED NOT NULL,
    vehiculo_id BIGINT UNSIGNED NOT NULL,
    responsable_id BIGINT UNSIGNED NOT NULL,
    motivo_ingreso TEXT NOT NULL,
    diagnostico TEXT NULL,
    diagnostico_autor_id BIGINT UNSIGNED NULL,
    estado VARCHAR(30) NOT NULL DEFAULT 'RECIBIDO',
    recibido_en DATETIME(3) NOT NULL,
    diagnosticado_en DATETIME(3) NULL,
    actualizado_en DATETIME(3) NOT NULL,
    iniciado_en DATETIME(3) NULL,
    finalizado_en DATETIME(3) NULL,
    entregado_en DATETIME(3) NULL,
    cancelado_en DATETIME(3) NULL,
    motivo_cancelacion TEXT NULL,
    presupuesto_autorizado_id BIGINT UNSIGNED NULL,
    trabajo_realizado TEXT NULL,
    ingreso_final DECIMAL(12,2) NULL,
    costo_repuestos_total DECIMAL(12,2) NULL,
    horas_mano_obra_total DECIMAL(8,2) NULL,
    tarifa_hora_aplicada DECIMAL(12,2) NULL,
    CONSTRAINT fk_orden_cliente FOREIGN KEY (cliente_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT fk_orden_vehiculo_titular FOREIGN KEY (vehiculo_id, cliente_id) REFERENCES vehiculo(id, cliente_id) ON DELETE RESTRICT,
    CONSTRAINT fk_orden_responsable FOREIGN KEY (responsable_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT fk_orden_diagnostico_autor FOREIGN KEY (diagnostico_autor_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT ck_orden_estado CHECK (estado IN (
      'RECIBIDO','DIAGNOSTICADO','PENDIENTE_APROBACION','APROBADO','EN_REPARACION',
      'FINALIZADO','ENTREGADO','RECHAZADO','CANCELADO')),
    CONSTRAINT ck_orden_motivo CHECK (CHAR_LENGTH(TRIM(motivo_ingreso)) > 0),
    CONSTRAINT ck_orden_diagnostico CHECK (
      estado NOT IN ('DIAGNOSTICADO','PENDIENTE_APROBACION','APROBADO','EN_REPARACION','FINALIZADO','ENTREGADO','RECHAZADO')
      OR (diagnostico IS NOT NULL AND CHAR_LENGTH(TRIM(diagnostico)) > 0
          AND diagnostico_autor_id IS NOT NULL AND diagnosticado_en IS NOT NULL)),
    CONSTRAINT ck_orden_autorizacion CHECK (
      estado NOT IN ('APROBADO','EN_REPARACION','FINALIZADO','ENTREGADO') OR presupuesto_autorizado_id IS NOT NULL),
    CONSTRAINT ck_orden_inicio CHECK (
      (estado IN ('EN_REPARACION','FINALIZADO','ENTREGADO') AND iniciado_en IS NOT NULL)
      OR (estado NOT IN ('EN_REPARACION','FINALIZADO','ENTREGADO') AND iniciado_en IS NULL)),
    CONSTRAINT ck_orden_cierre CHECK (
      (estado IN ('FINALIZADO','ENTREGADO') AND finalizado_en IS NOT NULL
       AND trabajo_realizado IS NOT NULL AND CHAR_LENGTH(TRIM(trabajo_realizado)) > 0
       AND ingreso_final IS NOT NULL AND ingreso_final >= 0
       AND costo_repuestos_total IS NOT NULL AND costo_repuestos_total >= 0
       AND horas_mano_obra_total IS NOT NULL AND horas_mano_obra_total >= 0
       AND tarifa_hora_aplicada IS NOT NULL AND tarifa_hora_aplicada > 0)
      OR (estado NOT IN ('FINALIZADO','ENTREGADO') AND finalizado_en IS NULL
          AND ingreso_final IS NULL AND costo_repuestos_total IS NULL
          AND horas_mano_obra_total IS NULL AND tarifa_hora_aplicada IS NULL)),
    CONSTRAINT ck_orden_entrega CHECK (
      (estado = 'ENTREGADO' AND entregado_en IS NOT NULL)
      OR (estado <> 'ENTREGADO' AND entregado_en IS NULL)),
    CONSTRAINT ck_orden_cancelacion CHECK (
      (estado = 'CANCELADO' AND cancelado_en IS NOT NULL AND motivo_cancelacion IS NOT NULL
       AND CHAR_LENGTH(TRIM(motivo_cancelacion)) > 0)
      OR (estado <> 'CANCELADO' AND cancelado_en IS NULL AND motivo_cancelacion IS NULL)),
    INDEX ix_orden_cliente_estado (cliente_id, estado),
    INDEX ix_orden_responsable_estado (responsable_id, estado),
    INDEX ix_orden_estado_finalizado (estado, finalizado_en)
) ENGINE=InnoDB;

CREATE TABLE presupuesto (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    orden_id BIGINT UNSIGNED NOT NULL,
    version INT NOT NULL,
    autor_id BIGINT UNSIGNED NOT NULL,
    publicado_en DATETIME(3) NOT NULL,
    vence_en DATETIME(3) NOT NULL,
    total DECIMAL(12,2) NOT NULL,
    decision VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE',
    decisor_id BIGINT UNSIGNED NULL,
    decidido_en DATETIME(3) NULL,
    CONSTRAINT uq_presupuesto_version UNIQUE (orden_id, version),
    CONSTRAINT uq_presupuesto_orden UNIQUE (id, orden_id),
    CONSTRAINT fk_presupuesto_orden FOREIGN KEY (orden_id) REFERENCES orden(id) ON DELETE RESTRICT,
    CONSTRAINT fk_presupuesto_autor FOREIGN KEY (autor_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT fk_presupuesto_decisor FOREIGN KEY (decisor_id) REFERENCES usuario(id) ON DELETE RESTRICT,
    CONSTRAINT ck_presupuesto_version CHECK (version > 0),
    CONSTRAINT ck_presupuesto_vencimiento CHECK (vence_en > publicado_en),
    CONSTRAINT ck_presupuesto_total CHECK (total >= 0),
    CONSTRAINT ck_presupuesto_decision CHECK (decision IN ('PENDIENTE','ACEPTADO','RECHAZADO','REVISION')),
    CONSTRAINT ck_presupuesto_respuesta CHECK (
      (decision = 'PENDIENTE' AND decisor_id IS NULL AND decidido_en IS NULL)
      OR (decision <> 'PENDIENTE' AND decisor_id IS NOT NULL AND decidido_en IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE renglon (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    presupuesto_id BIGINT UNSIGNED NOT NULL,
    numero INT NOT NULL,
    tipo VARCHAR(15) NOT NULL,
    descripcion VARCHAR(255) NOT NULL,
    cantidad DECIMAL(8,2) NOT NULL,
    precio_unitario DECIMAL(12,2) NOT NULL,
    CONSTRAINT uq_renglon_numero UNIQUE (presupuesto_id, numero),
    CONSTRAINT fk_renglon_presupuesto FOREIGN KEY (presupuesto_id) REFERENCES presupuesto(id) ON DELETE RESTRICT,
    CONSTRAINT ck_renglon_numero CHECK (numero > 0),
    CONSTRAINT ck_renglon_tipo CHECK (tipo IN ('REPUESTO','MANO_OBRA')),
    CONSTRAINT ck_renglon_descripcion CHECK (CHAR_LENGTH(TRIM(descripcion)) > 0),
    CONSTRAINT ck_renglon_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_renglon_precio CHECK (precio_unitario >= 0)
) ENGINE=InnoDB;
