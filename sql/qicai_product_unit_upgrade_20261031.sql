-- Optional audience units; existing product units and historical orders remain intact.
CREATE TABLE IF NOT EXISTS qc_product_unit (
  product_id BIGINT NOT NULL PRIMARY KEY,
  business_unit VARCHAR(20) DEFAULT NULL,
  consumer_unit VARCHAR(20) DEFAULT NULL,
  CONSTRAINT fk_qc_product_unit_product FOREIGN KEY (product_id) REFERENCES qc_product(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
