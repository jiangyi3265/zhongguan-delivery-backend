-- 企采通采购、配送、运营三端共享业务库
-- 本脚本可重复执行，不删除既有业务数据。
CREATE DATABASE IF NOT EXISTS qicai_delivery
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE qicai_delivery;

CREATE TABLE IF NOT EXISTS qc_customer (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  audience        VARCHAR(16)     NOT NULL COMMENT 'business/consumer',
  phone           VARCHAR(20)     NOT NULL,
  password_hash   VARCHAR(100)    NOT NULL,
  company_name    VARCHAR(120)    NULL,
  contact_name    VARCHAR(60)     NOT NULL,
  tax_id          VARCHAR(32)     NULL,
  address         VARCHAR(255)    NULL,
  status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING/ACTIVE/REJECTED/DISABLED',
  version         INT             NOT NULL DEFAULT 0,
  deleted         TINYINT         NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_customer_audience_phone (audience, phone),
  UNIQUE KEY uk_qc_customer_tax_id (tax_id),
  KEY idx_qc_customer_status (status),
  CONSTRAINT ck_qc_customer_audience CHECK (audience IN ('business', 'consumer'))
) ENGINE=InnoDB COMMENT='采购客户';

CREATE TABLE IF NOT EXISTS qc_courier (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  phone           VARCHAR(20)     NOT NULL,
  password_hash   VARCHAR(100)    NOT NULL,
  name            VARCHAR(60)     NOT NULL,
  vehicle_no      VARCHAR(32)     NULL,
  status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
  available       TINYINT         NOT NULL DEFAULT 1,
  version         INT             NOT NULL DEFAULT 0,
  deleted         TINYINT         NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_courier_phone (phone),
  KEY idx_qc_courier_available (status, available)
) ENGINE=InnoDB COMMENT='配送员';

CREATE TABLE IF NOT EXISTS qc_session (
  token_hash      CHAR(64)         NOT NULL,
  principal_type VARCHAR(16)      NOT NULL COMMENT 'customer/courier',
  principal_id   BIGINT           NOT NULL,
  audience       VARCHAR(16)      NOT NULL,
  expires_at     DATETIME         NOT NULL,
  created_at     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token_hash),
  KEY idx_qc_session_principal (principal_type, principal_id),
  KEY idx_qc_session_expiry (expires_at)
) ENGINE=InnoDB COMMENT='移动端会话';

CREATE TABLE IF NOT EXISTS qc_sms_code (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  phone           VARCHAR(20)     NOT NULL,
  audience        VARCHAR(16)     NOT NULL,
  code_hash       CHAR(64)        NOT NULL,
  expires_at      DATETIME        NOT NULL,
  consumed        TINYINT         NOT NULL DEFAULT 0,
  attempts        INT             NOT NULL DEFAULT 0,
  requested_ip    VARCHAR(64)     NULL,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_qc_sms_phone (phone, audience, created_at),
  KEY idx_qc_sms_expiry (expires_at)
) ENGINE=InnoDB COMMENT='短信验证码';

CREATE TABLE IF NOT EXISTS qc_product (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  sku             VARCHAR(64)     NOT NULL,
  name            VARCHAR(160)    NOT NULL,
  subtitle        VARCHAR(255)    NULL,
  category        VARCHAR(60)     NOT NULL,
  unit            VARCHAR(20)     NOT NULL,
  stock           INT             NOT NULL DEFAULT 0,
  business_price  DECIMAL(12,2)   NOT NULL,
  consumer_price  DECIMAL(12,2)   NOT NULL,
  status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
  version         INT             NOT NULL DEFAULT 0,
  deleted         TINYINT         NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_product_sku (sku),
  KEY idx_qc_product_status_category (status, category),
  CONSTRAINT ck_qc_product_stock CHECK (stock >= 0),
  CONSTRAINT ck_qc_product_price CHECK (business_price >= 0 AND consumer_price >= 0)
) ENGINE=InnoDB COMMENT='商品';

CREATE TABLE IF NOT EXISTS qc_coupon (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  name            VARCHAR(120)    NOT NULL,
  audience        VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'all/business/consumer',
  threshold_amount DECIMAL(12,2)  NOT NULL DEFAULT 0,
  discount_amount DECIMAL(12,2)   NOT NULL,
  valid_from      DATETIME        NOT NULL,
  valid_to        DATETIME        NOT NULL,
  status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
  deleted         TINYINT         NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_qc_coupon_audience_status (audience, status, valid_to),
  CONSTRAINT ck_qc_coupon_amount CHECK (threshold_amount >= 0 AND discount_amount > 0)
) ENGINE=InnoDB COMMENT='优惠券模板';

CREATE TABLE IF NOT EXISTS qc_customer_coupon (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  customer_id     BIGINT          NOT NULL,
  coupon_id       BIGINT          NOT NULL,
  status          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/USED/EXPIRED',
  used_order_id   BIGINT          NULL,
  claimed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  used_at         DATETIME        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_customer_coupon (customer_id, coupon_id),
  KEY idx_qc_customer_coupon_status (customer_id, status),
  CONSTRAINT fk_qc_customer_coupon_customer FOREIGN KEY (customer_id) REFERENCES qc_customer(id),
  CONSTRAINT fk_qc_customer_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES qc_coupon(id)
) ENGINE=InnoDB COMMENT='客户优惠券';

CREATE TABLE IF NOT EXISTS qc_order (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  order_no        VARCHAR(40)     NOT NULL,
  customer_id     BIGINT          NOT NULL,
  audience        VARCHAR(16)     NOT NULL,
  status          VARCHAR(24)     NOT NULL DEFAULT 'PENDING_SHIPMENT',
  subtotal_amount DECIMAL(12,2)   NOT NULL,
  discount_amount DECIMAL(12,2)   NOT NULL DEFAULT 0,
  payable_amount  DECIMAL(12,2)   NOT NULL,
  contact_name    VARCHAR(60)     NOT NULL,
  contact_phone   VARCHAR(20)     NOT NULL,
  delivery_address VARCHAR(255)   NOT NULL,
  remark          VARCHAR(255)    NULL,
  customer_coupon_id BIGINT       NULL,
  idempotency_key VARCHAR(64)     NOT NULL,
  version         INT             NOT NULL DEFAULT 0,
  deleted         TINYINT         NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_order_no (order_no),
  UNIQUE KEY uk_qc_order_idempotency (customer_id, idempotency_key),
  KEY idx_qc_order_customer_status (customer_id, status, created_at),
  KEY idx_qc_order_status_created (status, created_at),
  CONSTRAINT fk_qc_order_customer FOREIGN KEY (customer_id) REFERENCES qc_customer(id),
  CONSTRAINT ck_qc_order_amount CHECK (subtotal_amount >= 0 AND discount_amount >= 0 AND payable_amount >= 0)
) ENGINE=InnoDB COMMENT='采购订单';

CREATE TABLE IF NOT EXISTS qc_order_item (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  order_id        BIGINT          NOT NULL,
  product_id      BIGINT          NOT NULL,
  sku             VARCHAR(64)     NOT NULL,
  product_name    VARCHAR(160)    NOT NULL,
  unit            VARCHAR(20)     NOT NULL,
  unit_price      DECIMAL(12,2)   NOT NULL,
  quantity        INT             NOT NULL,
  line_amount     DECIMAL(12,2)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_qc_order_item_order (order_id),
  CONSTRAINT fk_qc_order_item_order FOREIGN KEY (order_id) REFERENCES qc_order(id),
  CONSTRAINT fk_qc_order_item_product FOREIGN KEY (product_id) REFERENCES qc_product(id),
  CONSTRAINT ck_qc_order_item_quantity CHECK (quantity > 0)
) ENGINE=InnoDB COMMENT='订单明细';

CREATE TABLE IF NOT EXISTS qc_delivery_task (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  task_no         VARCHAR(40)     NOT NULL,
  order_id        BIGINT          NOT NULL,
  courier_id      BIGINT          NULL,
  status          VARCHAR(24)     NOT NULL DEFAULT 'WAITING_ASSIGN',
  warehouse_name  VARCHAR(120)    NOT NULL DEFAULT '龙翔中心仓',
  warehouse_address VARCHAR(255)  NOT NULL DEFAULT '深圳市南山区仓储路 9 号',
  delivery_window VARCHAR(80)     NULL,
  distance_km     DECIMAL(8,2)    NULL,
  pickup_proof_url VARCHAR(500)   NULL,
  delivery_proof_url VARCHAR(500) NULL,
  signed_name     VARCHAR(60)     NULL,
  version         INT             NOT NULL DEFAULT 0,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_delivery_task_no (task_no),
  UNIQUE KEY uk_qc_delivery_order (order_id),
  KEY idx_qc_delivery_courier_status (courier_id, status),
  CONSTRAINT fk_qc_delivery_order FOREIGN KEY (order_id) REFERENCES qc_order(id),
  CONSTRAINT fk_qc_delivery_courier FOREIGN KEY (courier_id) REFERENCES qc_courier(id)
) ENGINE=InnoDB COMMENT='配送任务';

CREATE TABLE IF NOT EXISTS qc_delivery_event (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  delivery_id     BIGINT          NOT NULL,
  status          VARCHAR(24)     NOT NULL,
  note            VARCHAR(255)    NULL,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_qc_delivery_event_task (delivery_id, created_at),
  CONSTRAINT fk_qc_delivery_event_task FOREIGN KEY (delivery_id) REFERENCES qc_delivery_task(id)
) ENGINE=InnoDB COMMENT='配送轨迹';

INSERT INTO qc_product (id, sku, name, subtitle, category, unit, stock, business_price, consumer_price, status)
VALUES
  (1, 'JG-DY-5LX4', '金谷源一级大豆油 5L×4箱', '非转基因 · 物理压榨', '食品采购', '箱', 328, 219.00, 249.00, 'ACTIVE'),
  (2, 'MG-A4-80G', '晨光高白静电复印纸 A4 80g', '500张/包 · 5包/箱', '办公耗材', '箱', 510, 139.00, 159.00, 'ACTIVE'),
  (3, 'QC-CLEAN-10', '商用浓缩洗洁精 10kg', '低泡易冲洗 · 柠檬清香', '清洁用品', '桶', 86, 89.00, 108.00, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name), subtitle = VALUES(subtitle), category = VALUES(category), unit = VALUES(unit),
  business_price = VALUES(business_price), consumer_price = VALUES(consumer_price), status = 'ACTIVE';

INSERT INTO qc_coupon (id, name, audience, threshold_amount, discount_amount, valid_from, valid_to, status)
VALUES
  (1, '月度集采满减券', 'business', 2000.00, 100.00, '2026-01-01 00:00:00', '2027-12-31 23:59:59', 'ACTIVE'),
  (2, '办公耗材采购券', 'all', 1000.00, 50.00, '2026-01-01 00:00:00', '2027-12-31 23:59:59', 'ACTIVE'),
  (3, '新用户专享券', 'consumer', 300.00, 30.00, '2026-01-01 00:00:00', '2027-12-31 23:59:59', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name), audience = VALUES(audience), threshold_amount = VALUES(threshold_amount),
  discount_amount = VALUES(discount_amount), valid_to = VALUES(valid_to), status = 'ACTIVE';
