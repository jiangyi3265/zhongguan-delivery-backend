# 部署与重新构建

## 三端构建

后端在仓库根目录执行 `mvn clean package`，产物为 `ruoyi-admin/target/ruoyi-admin.jar`。

后台按 README 复制环境模板，执行 `pnpm install --frozen-lockfile` 和 `pnpm build:prod`，产物为 `dist/`。

小程序执行 `pnpm install --frozen-lockfile`，分别执行 `pnpm build:h5` 和 `pnpm build:mp-weixin`。微信构建需要 `VITE_QICAI_API_BASE` 为实际 HTTPS API 地址。产物分别为 `dist/build/h5/`、`dist/build/mp-weixin/`。

## 新环境数据库

准备 MySQL 8 空库 `qicai_delivery`，运行 `python -m pip install -r deploy/requirements.txt`，然后执行 `python deploy/initialize-db.py --user <初始化数据库账号>`。工具交互获取密码，要求库中没有表，创建全部结构和新管理员凭据。数据库账户应拥有该库建表、建索引与变更表的权限。

运行账户通过 `QICAI_DB_USERNAME`、`QICAI_DB_PASSWORD` 配置。为应用账号授予该库业务读写权限。Token 签名密钥生成后保存到服务器外部环境文件，重启时继续使用该值。Redis 和上传目录配置参见仓库根目录 `.env.example`。

初始化出现异常时会保留错误位置和已执行结构，需在新的空库修复后重试。已有业务库应先完成备份，再按所缺版本执行增量迁移。

## 已有库升级

基础 `ry_20250522.sql` 是首次建库脚本。`qicai_delivery.sql` 包含历史验收商品种子。对已有数据使用对应增量脚本，按下列顺序核对。

1. `qicai_delivery_upgrade_20260926.sql`
2. `qicai_payment_upgrade_20261007.sql`
3. `qicai_workflow_upgrade_20261015.sql`
4. `qicai_storefront_upgrade_20261017.sql`
5. `qicai_commercial_remediation_upgrade_20261017.sql`
6. `qicai_extensions_upgrade_20261018.sql`
7. `qicai_brand_upgrade_20261019.sql`
8. `qicai_product_unit_upgrade_20261031.sql`
9. `qicai_forward_warehouse_upgrade_20261101.sql`
10. `qicai_sales_names_upgrade_20261102.sql`

`qicai_delivery_upgrade_20260904.sql` 用于更早版本数据库，当前基础表已经包含它的字段。执行前检查字段和 `qc_schema_version`，避免重复执行旧 ALTER TABLE。

## 服务与静态站点

推荐将每次构建放在独立发布目录，包含 `backend/ruoyi-admin.jar`、`admin/`、`h5/`、`mp-weixin/`、`sql/` 和 `deploy/`。上传文件、数据库、日志和外部环境配置放在共享目录。通过当前版本指针切换发布目录，保留可回退版本。

后端示例启动命令如下，配置文件路径由部署环境确定。

```bash
java -jar backend/ruoyi-admin.jar --spring.profiles.active=qicai --spring.config.additional-location=file:/etc/qicai/application-server.yml
```

服务进程从 `/etc/qicai/app.env` 或服务管理器读取环境变量。`application-server.yml` 提供回环监听和共享上传目录示例，部署时按服务器调整端口、路径和可信域名。

Nginx 模板 `nginx-live.template.conf` 提供管理后台、H5/API、图片、微信支付回调的反向代理规则。替换域名与证书路径，管理站点配置 `/prod-api/`，小程序/H5 站点配置 `/qicai/mobile/` 与 `/qicai/payment/wechat/notify`。图像上传大小应与应用上传规则匹配。验证 `nginx -t` 后加载配置。

后台地图使用 `warehouse-map-cache.conf` 和 `warehouse-map-location.conf`，分别放在 http 层和后台 server 层。高德坐标拾取器通过外链使用，具体坐标录入在后台完成。

## 微信与数据恢复

启用微信登录需要实际 AppID 和 AppSecret 文件。支付还需要已关联商户号、商户证书序列号、私钥、微信支付公钥及 ID、APIv3 密钥文件和公网 HTTPS 回调地址。文件放在外部私有目录，服务账号可读，参数名见 `.env.example`。

数据库和上传目录共同构成完整业务数据。`backup.py` 提供数据库、文件、校验清单的备份与校验逻辑，配置路径和 MySQL 程序位置后使用。恢复在独立环境先校验，再切换。支付、退款和履约记录应与迁移后的 schema 一同保留。

`install.py`、`finalize.py`、`upgrade-20260926.py` 与 `verify-*` 保存了旧服务器部署和验收逻辑，其中包含固定路径和历史清理操作。它们的适用环境见 `HISTORY.md`，当前新环境使用上面的初始化与构建步骤。
