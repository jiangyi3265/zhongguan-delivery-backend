-- Additive workflow migration; run with an explicitly selected database.
SET @has_exception_from = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='qc_delivery_task' AND column_name='exception_from');
SET @workflow_ddl = IF(@has_exception_from=0,
  'ALTER TABLE qc_delivery_task ADD COLUMN exception_from VARCHAR(24) NULL AFTER status', 'SELECT 1');
PREPARE workflow_migration FROM @workflow_ddl;
EXECUTE workflow_migration;
DEALLOCATE PREPARE workflow_migration;
-- Legacy exception reports kept the order at its prior stage. Only backfill
-- when the persisted last non-exception event independently agrees with it.
UPDATE qc_delivery_task d JOIN qc_order o ON o.id=d.order_id
SET d.exception_from=o.status
WHERE d.status='EXCEPTION' AND d.exception_from IS NULL
  AND o.status IN ('ASSIGNED','PICKED_UP','IN_DELIVERY')
  AND o.status=(SELECT e.status FROM qc_delivery_event e
    WHERE e.delivery_id=d.id AND e.status<>'EXCEPTION' ORDER BY e.id DESC LIMIT 1);

CREATE TABLE IF NOT EXISTS qc_payment_maintenance (
  order_id BIGINT NOT NULL PRIMARY KEY,
  status VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(255) NULL,
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  next_attempt_at DATETIME NULL,
  CONSTRAINT fk_qc_maintenance_order FOREIGN KEY (order_id) REFERENCES qc_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qc_refund (
  order_id BIGINT NOT NULL PRIMARY KEY,
  out_refund_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  refund_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  amount_fen INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
  reason VARCHAR(80) NOT NULL,
  requested_by BIGINT NOT NULL,
  requested_name VARCHAR(64) NOT NULL,
  reviewed_by BIGINT NULL,
  reviewed_name VARCHAR(64) NULL,
  review_note VARCHAR(255) NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at DATETIME NULL,
  success_at DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(255) NULL,
  next_attempt_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_qc_refund_out_no(out_refund_no),
  UNIQUE KEY uk_qc_refund_provider_id(refund_id),
  KEY idx_qc_refund_due(status,next_attempt_at),
  CONSTRAINT ck_qc_refund_amount CHECK (amount_fen>0),
  CONSTRAINT fk_qc_refund_order FOREIGN KEY (order_id) REFERENCES qc_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qc_refund_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  actor_id BIGINT NULL,
  actor_name VARCHAR(64) NULL,
  note VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_qc_refund_event_order(order_id,id),
  CONSTRAINT fk_qc_refund_event_order FOREIGN KEY (order_id) REFERENCES qc_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
