-- Additive, repeatable upgrade. Existing orders and notices are retained.
CREATE TABLE IF NOT EXISTS qc_forward_warehouse (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 address VARCHAR(255) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL,
 longitude DECIMAL(11,7) NOT NULL,
 opening_hours VARCHAR(120) NOT NULL DEFAULT '',
 sort_order INT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'INACTIVE',
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_qc_forward_location CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180),
 CONSTRAINT ck_qc_forward_status CHECK (status IN ('ACTIVE','INACTIVE')),
 INDEX idx_qc_forward_visible(status,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_order_warehouse (
 order_id BIGINT NOT NULL PRIMARY KEY,
 warehouse_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL,
 address VARCHAR(255) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL,
 longitude DECIMAL(11,7) NOT NULL,
 opening_hours VARCHAR(120) NOT NULL DEFAULT '',
 warehouse_version INT NOT NULL,
 CONSTRAINT fk_qc_order_warehouse_order FOREIGN KEY(order_id) REFERENCES qc_order(id),
 CONSTRAINT fk_qc_order_warehouse_point FOREIGN KEY(warehouse_id) REFERENCES qc_forward_warehouse(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_notice_hidden (
 notice_id BIGINT NOT NULL,
 principal_type VARCHAR(16) NOT NULL,
 principal_id BIGINT NOT NULL,
 hidden_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(notice_id,principal_type,principal_id),
 CONSTRAINT fk_qc_notice_hidden_notice FOREIGN KEY(notice_id) REFERENCES qc_order_notice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO qc_schema_version(version) VALUES('forward-warehouse-20261101');
