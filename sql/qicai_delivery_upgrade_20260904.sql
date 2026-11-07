USE qicai_delivery;

-- Existing installations created before 2026-09-04 should run this once.
ALTER TABLE qc_order
  ADD COLUMN remark VARCHAR(255) NULL AFTER delivery_address;

ALTER TABLE qc_courier
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER available,
  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER version;
