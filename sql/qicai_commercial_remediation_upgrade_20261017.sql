-- Additive commercial remediation. Requires an explicitly selected isolated/approved database.
-- Apply after all previous qicai migrations. Never run the original demo seed in production.
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_product' AND column_name='stock' AND data_type='bigint');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_product MODIFY COLUMN stock BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='qc_product' AND constraint_name='ck_qc_product_stock_safe');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_product ADD CONSTRAINT ck_qc_product_stock_safe CHECK (stock BETWEEN 0 AND 9007199254740991)','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_delivery_event' AND column_name='note' AND character_maximum_length>=512);
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_delivery_event MODIFY COLUMN note VARCHAR(512) NULL','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_coupon' AND column_name='version');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_coupon ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_order' AND column_name='delivery_latitude');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_order ADD COLUMN delivery_latitude DECIMAL(10,7) NULL AFTER delivery_address','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_order' AND column_name='delivery_longitude');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_order ADD COLUMN delivery_longitude DECIMAL(10,7) NULL AFTER delivery_latitude','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_order' AND column_name='delivery_location_name');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_order ADD COLUMN delivery_location_name VARCHAR(120) NULL AFTER delivery_longitude','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_delivery_event' AND column_name='actor_type');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_delivery_event ADD COLUMN actor_type VARCHAR(16) NULL AFTER note','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_delivery_event' AND column_name='actor_id');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_delivery_event ADD COLUMN actor_id BIGINT NULL AFTER actor_type','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='qc_delivery_event' AND column_name='actor_name');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_delivery_event ADD COLUMN actor_name VARCHAR(64) NULL AFTER actor_id','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;
SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='qc_customer_coupon' AND index_name='idx_qc_customer_coupon_used_order');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_customer_coupon ADD INDEX idx_qc_customer_coupon_used_order(used_order_id,status)','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;

SET @remediation_exists=(SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='qc_order' AND constraint_name='ck_qc_order_location');
SET @remediation_ddl=IF(@remediation_exists=0,'ALTER TABLE qc_order ADD CONSTRAINT ck_qc_order_location CHECK ((delivery_latitude IS NULL AND delivery_longitude IS NULL AND delivery_location_name IS NULL) OR (delivery_latitude IS NOT NULL AND delivery_longitude IS NOT NULL AND delivery_location_name IS NOT NULL AND delivery_latitude BETWEEN -90 AND 90 AND delivery_longitude BETWEEN -180 AND 180 AND CHAR_LENGTH(TRIM(delivery_location_name)) BETWEEN 1 AND 120))','SELECT 1');
PREPARE remediation_migration FROM @remediation_ddl; EXECUTE remediation_migration; DEALLOCATE PREPARE remediation_migration;

CREATE TABLE IF NOT EXISTS qc_business_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_type VARCHAR(16) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(64) NOT NULL,
  action VARCHAR(40) NOT NULL,
  object_type VARCHAR(24) NOT NULL,
  object_id BIGINT NOT NULL,
  before_values JSON NULL,
  after_values JSON NULL,
  related_event_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_qc_business_audit_object(object_type,object_id,id),
  KEY idx_qc_business_audit_actor(actor_type,actor_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qc_schema_version (
  version VARCHAR(64) NOT NULL PRIMARY KEY,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Last statement: a failed migration must never receive its readiness marker.
INSERT IGNORE INTO qc_schema_version(version) VALUES('commercial-remediation-20261017');
