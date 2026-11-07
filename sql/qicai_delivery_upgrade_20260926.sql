-- Additive migration only. Run against the explicitly selected qicai database.
CREATE TABLE IF NOT EXISTS qc_upload (
  file_name VARCHAR(500) COLLATE utf8mb4_bin NOT NULL,
  principal_type VARCHAR(16) NOT NULL,
  principal_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (file_name),
  KEY idx_qc_upload_principal (principal_type, principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Authenticated mobile upload ownership';

CREATE TABLE IF NOT EXISTS qc_delivery_attachment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  delivery_id BIGINT NOT NULL,
  phase VARCHAR(16) NOT NULL,
  file_name VARCHAR(500) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_delivery_attachment (delivery_id, phase, file_name),
  CONSTRAINT fk_qc_attachment_delivery FOREIGN KEY (delivery_id) REFERENCES qc_delivery_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='All uploaded pickup and receipt images';
