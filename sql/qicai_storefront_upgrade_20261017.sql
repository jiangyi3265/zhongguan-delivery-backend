-- Additive storefront migration. Explicitly select an isolated/approved database.
-- No account is merged by phone; existing password accounts keep their values.
SET @storefront_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='qc_customer' AND column_name='auth_method');
SET @storefront_ddl = IF(@storefront_exists=0,
  'ALTER TABLE qc_customer ADD COLUMN auth_method VARCHAR(16) NOT NULL DEFAULT ''PASSWORD'' AFTER audience', 'SELECT 1');
PREPARE storefront_migration FROM @storefront_ddl;
EXECUTE storefront_migration;
DEALLOCATE PREPARE storefront_migration;
SET @storefront_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='qc_customer' AND column_name='contact_phone');
SET @storefront_ddl = IF(@storefront_exists=0,
  'ALTER TABLE qc_customer ADD COLUMN contact_phone VARCHAR(20) NULL AFTER phone', 'SELECT 1');
PREPARE storefront_migration FROM @storefront_ddl;
EXECUTE storefront_migration;
DEALLOCATE PREPARE storefront_migration;
ALTER TABLE qc_customer
  MODIFY COLUMN phone VARCHAR(20) NULL,
  MODIFY COLUMN password_hash VARCHAR(100) NULL,
  MODIFY COLUMN contact_name VARCHAR(60) NULL;

CREATE TABLE IF NOT EXISTS qc_wechat_account (
  app_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  open_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  consumer_customer_id BIGINT NULL,
  business_customer_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (app_id,open_id),
  UNIQUE KEY uk_qc_wechat_account_consumer(consumer_customer_id),
  UNIQUE KEY uk_qc_wechat_account_business(business_customer_id),
  CONSTRAINT fk_qc_wechat_account_consumer FOREIGN KEY (consumer_customer_id) REFERENCES qc_customer(id),
  CONSTRAINT fk_qc_wechat_account_business FOREIGN KEY (business_customer_id) REFERENCES qc_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
