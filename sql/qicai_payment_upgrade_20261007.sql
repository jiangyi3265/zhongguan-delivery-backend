-- Additive; select the database explicitly. Repeat execution is safe.
-- Existing orders keep NOT_REQUIRED: do not retroactively charge them.
SET @payment_column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='qc_order' AND column_name='payment_status');
SET @payment_ddl = IF(@payment_column_exists=0,
  'ALTER TABLE qc_order ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT ''NOT_REQUIRED'' AFTER status', 'SELECT 1');
PREPARE payment_migration FROM @payment_ddl;
EXECUTE payment_migration;
DEALLOCATE PREPARE payment_migration;

CREATE TABLE IF NOT EXISTS qc_wechat_identity (
  customer_id BIGINT NOT NULL,
  app_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  open_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id, app_id),
  KEY idx_qc_wechat_openid (app_id, open_id),
  CONSTRAINT fk_qc_wechat_customer FOREIGN KEY (customer_id) REFERENCES qc_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS qc_payment (
  order_id BIGINT NOT NULL,
  out_trade_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  merchant_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  app_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  payer_open_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  amount_fen INT NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
  status VARCHAR(16) NOT NULL DEFAULT 'PREPARED',
  uncertain_attempts INT NOT NULL DEFAULT 0,
  transaction_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  paid_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_qc_payment_trade (out_trade_no),
  UNIQUE KEY uk_qc_payment_transaction (transaction_id),
  CONSTRAINT ck_qc_payment_amount CHECK (amount_fen > 0),
  CONSTRAINT fk_qc_payment_order FOREIGN KEY (order_id) REFERENCES qc_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Preserve earlier candidate PENDING records as unknown, never assume zero sends.
SET @attempt_column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='qc_payment' AND column_name='uncertain_attempts');
SET @attempt_ddl = IF(@attempt_column_exists=0,
  'ALTER TABLE qc_payment ADD COLUMN uncertain_attempts INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE attempt_migration FROM @attempt_ddl;
EXECUTE attempt_migration;
DEALLOCATE PREPARE attempt_migration;
-- Backfill above preserves unknown old sends, while future records start unsent.
ALTER TABLE qc_payment ALTER COLUMN uncertain_attempts SET DEFAULT 0;
ALTER TABLE qc_payment ALTER COLUMN status SET DEFAULT 'PREPARED';
