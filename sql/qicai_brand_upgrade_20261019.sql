-- Additive brand catalogue. Existing product, price, stock and order rows stay unchanged.
CREATE TABLE IF NOT EXISTS qc_brand (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(60) NOT NULL,
  description VARCHAR(240) NOT NULL DEFAULT '',
  logo_url VARCHAR(180) NOT NULL DEFAULT '',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_qc_brand_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_brand_slide (
  brand_id BIGINT NOT NULL,
  position INT NOT NULL,
  image_url VARCHAR(180) NOT NULL,
  caption VARCHAR(80) NOT NULL DEFAULT '',
  PRIMARY KEY (brand_id,position),
  CONSTRAINT fk_qc_brand_slide FOREIGN KEY(brand_id) REFERENCES qc_brand(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_product_brand (
  product_id BIGINT NOT NULL PRIMARY KEY,
  brand_id BIGINT NOT NULL,
  KEY idx_qc_product_brand(brand_id,product_id),
  CONSTRAINT fk_qc_product_brand_product FOREIGN KEY(product_id) REFERENCES qc_product(id),
  CONSTRAINT fk_qc_product_brand_brand FOREIGN KEY(brand_id) REFERENCES qc_brand(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- User-provided brand identity. Repeat runs preserve subsequent admin edits.
INSERT INTO qc_brand(code,name,logo_url)
SELECT 'PPS','泼皮侠 PPS','/static/brands/pps-icon.jpg'
WHERE NOT EXISTS (SELECT 1 FROM qc_brand WHERE code='PPS');
INSERT IGNORE INTO qc_brand_slide(brand_id,position,image_url,caption)
SELECT id,0,'/static/brands/pps-intro.jpg','泼皮侠 PPS' FROM qc_brand WHERE code='PPS' AND version=0;
-- Explicitly approved six test products only. No inference from categories or names.
INSERT IGNORE INTO qc_product_brand(product_id,brand_id)
SELECT p.id,b.id FROM qc_product p JOIN qc_brand b ON b.code='PPS'
WHERE NOT EXISTS (SELECT 1 FROM qc_schema_version WHERE version='brands-20261019')
AND p.sku IN ('TEST-20261018-RICE-001','TEST-20261018-OIL-002','TEST-20261018-LAUNDRY-003',
 'TEST-20261018-DETERGENT-004','TEST-20261018-PAPER-005','TEST-20261018-TISSUE-006');
INSERT IGNORE INTO qc_schema_version(version) VALUES('brands-20261019');
