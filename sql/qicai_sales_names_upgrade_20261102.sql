-- Additive and repeatable. Existing product and order names remain intact.
CREATE TABLE IF NOT EXISTS qc_product_name (
 product_id BIGINT NOT NULL PRIMARY KEY,
 business_name VARCHAR(160) NULL,
 consumer_name VARCHAR(160) NULL,
 CONSTRAINT fk_qc_product_name FOREIGN KEY(product_id) REFERENCES qc_product(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_sales_profile (
 user_id BIGINT NOT NULL PRIMARY KEY,
 invite_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 version INT NOT NULL DEFAULT 0,
 CONSTRAINT fk_qc_sales_user FOREIGN KEY(user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS qc_customer_sales (
 customer_id BIGINT NOT NULL PRIMARY KEY,
 sales_user_id BIGINT NOT NULL,
 assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_qc_customer_sales_customer FOREIGN KEY(customer_id) REFERENCES qc_customer(id),
 CONSTRAINT fk_qc_customer_sales_owner FOREIGN KEY(sales_user_id) REFERENCES qc_sales_profile(user_id),
 INDEX idx_qc_customer_sales_owner(sales_user_id,customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark)
 SELECT '销售员（本人客户审核）','qicai_sales',20,'5','0','0','qicai',NOW(),'仅审核本人名下待审B端注册申请'
 WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='qicai_sales');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
 SELECT '我的客户审核',0,20,'sales-reviews','qicai/sales','QicaiSalesReviews',1,1,'C','0','0','qicai:sales:review','user','qicai',NOW()
 WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='qicai:sales:review');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
 SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms='qicai:sales:review' WHERE r.role_key='qicai_sales';
INSERT IGNORE INTO qc_schema_version(version) VALUES('sales-names-20261102');
