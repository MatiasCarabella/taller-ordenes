ALTER TABLE orden ADD CONSTRAINT fk_orden_presupuesto_autorizado
    FOREIGN KEY (presupuesto_autorizado_id, id)
    REFERENCES presupuesto(id, orden_id) ON DELETE RESTRICT;
