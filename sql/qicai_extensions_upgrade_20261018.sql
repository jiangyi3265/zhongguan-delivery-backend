-- Additive extension. No existing business rows or historical migration files change.
CREATE TABLE IF NOT EXISTS qc_product_image (
  image_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  byte_size INT NOT NULL, width INT NOT NULL, height INT NOT NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_recon_bill (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, bill_date DATE NOT NULL,
  source_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL, merchant_id VARCHAR(32) NOT NULL,
  normalized_rows MEDIUMTEXT NOT NULL, imported_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_qc_recon_source(merchant_id,bill_date,source_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_recon_run (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, bill_id BIGINT NOT NULL,
  bill_date DATE NOT NULL, row_count INT NOT NULL, difference_count INT NOT NULL,
  order_amount DECIMAL(18,2) NOT NULL, refund_amount DECIMAL(18,2) NOT NULL,
  settled_amount DECIMAL(18,2) NOT NULL, settled_refund DECIMAL(18,2) NOT NULL,
  fee_amount DECIMAL(18,2) NOT NULL, net_amount DECIMAL(18,2) NOT NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_qc_recon_date(bill_date,id), CONSTRAINT fk_qc_recon_run_bill FOREIGN KEY(bill_id) REFERENCES qc_recon_bill(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_recon_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, run_id BIGINT NOT NULL,
  source_line INT NOT NULL, kind VARCHAR(16) NOT NULL, trade_no VARCHAR(64) NOT NULL,
  refund_no VARCHAR(64) NULL, order_id BIGINT NULL, outcome VARCHAR(24) NOT NULL,
  message VARCHAR(512) NOT NULL, local_amount DECIMAL(18,2) NULL, bill_amount DECIMAL(18,2) NULL,
  fee_amount DECIMAL(18,2) NULL,
  KEY idx_qc_recon_item(run_id,outcome,id), CONSTRAINT fk_qc_recon_item_run FOREIGN KEY(run_id) REFERENCES qc_recon_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_product_media (
  product_id BIGINT NOT NULL PRIMARY KEY, image_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_qc_media_product FOREIGN KEY(product_id) REFERENCES qc_product(id),
  CONSTRAINT fk_qc_media_image FOREIGN KEY(image_hash) REFERENCES qc_product_image(image_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_order_item_media (
  order_item_id BIGINT NOT NULL PRIMARY KEY, image_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_qc_item_media_order FOREIGN KEY(order_item_id) REFERENCES qc_order_item(id),
  CONSTRAINT fk_qc_item_media_image FOREIGN KEY(image_hash) REFERENCES qc_product_image(image_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_order_notice (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL,
  task_id BIGINT NULL, event_key VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  event_type VARCHAR(32) NOT NULL, recipient_type VARCHAR(16) NOT NULL,
  recipient_id BIGINT NOT NULL, title VARCHAR(80) NOT NULL, content VARCHAR(512) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_qc_notice_event(order_id,event_key,recipient_type,recipient_id),
  KEY idx_qc_notice_inbox(recipient_type,recipient_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_notice_read (
  notice_id BIGINT NOT NULL, principal_type VARCHAR(16) NOT NULL,
  principal_id BIGINT NOT NULL, read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(notice_id,principal_type,principal_id),
  CONSTRAINT fk_qc_notice_read FOREIGN KEY(notice_id) REFERENCES qc_order_notice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO qc_schema_version(version) VALUES('extensions-20261018');
