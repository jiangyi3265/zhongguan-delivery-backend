# 中关数字产业采购配送后端

为企业采购、个人零售、销售员审核和配送履约提供统一 API 与业务数据服务。

## 项目简介

- 支持 B/C 客户注册、微信身份登录、企业资料审核、销售员邀请码及本人客户审核权限。
- 管理品牌、轮播图、商品主图、分类、SKU、B/C 名称、单位、价格及共享库存。
- 实现订单核价、优惠券、幂等下单、微信支付验签与查单、超时关单、受控全额退款和交易账单核对。
- 支持前置仓、C 端按仓归集订单、配送指派、取货、路线位置、签收凭证与配送异常处理。
- 提供站内订单消息、隐私协议、资料管理、业务开关及系统角色权限。

## 技术栈

Java、Maven 多模块、Spring Boot 2.5.15、Spring Security 5.7.14、MyBatis、PageHelper、Druid、MySQL 8、Redis、微信支付 API v3 Java SDK 0.2.17。

源码编译目标为 Java 8，本交付使用 JDK 17 构建和运行。部署辅助工具使用 Python 3。实际依赖版本由各模块 `pom.xml` 管理。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| zhongguan-delivery-backend | 后端服务 | [zhongguan-delivery-backend](https://github.com/jiangyi3265/zhongguan-delivery-backend) |
| zhongguan-delivery-admin | 管理后台 | [zhongguan-delivery-admin](https://github.com/jiangyi3265/zhongguan-delivery-admin) |
| zhongguan-delivery-app | 用户端与配送员端 | [zhongguan-delivery-app](https://github.com/jiangyi3265/zhongguan-delivery-app) |

## 快速启动

准备 JDK 17、Maven 3.9、MySQL 8、Redis、Python 3，并在 MySQL 中创建空数据库。

```sql
CREATE DATABASE qicai_delivery CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

安装初始化工具依赖，交互输入数据库密码和新管理员密码。

```bash
python -m pip install -r deploy/requirements.txt
python deploy/initialize-db.py --user root
```

初始化器要求数据库为空，依次执行基础表和全部现有升级脚本，并跳过演示商品及优惠券。若初始化中断，先处理报错，在新建空库中重试。已有业务库按 `deploy/README.md` 的增量迁移流程维护。

根据 `.env.example` 在当前进程配置数据库、Redis、上传路径及随机 Token 密钥。Spring Boot 通过环境变量读取配置。以下为 PowerShell 启动示例，变量中的密码由终端交互获取。

```powershell
$env:QICAI_DB_USERNAME='root'
$env:QICAI_DB_PASSWORD=[System.Net.NetworkCredential]::new('', (Read-Host '数据库密码' -AsSecureString)).Password
$env:QICAI_TOKEN_SECRET=python -c "import secrets; print(secrets.token_hex(48))"
$env:RUOYI_PROFILE=Join-Path $PWD 'runtime/uploads'
$env:QICAI_LOG_PATH=Join-Path $PWD 'runtime/logs'
# 根据 Redis 实例填写 SPRING_REDIS_HOST、SPRING_REDIS_PORT、SPRING_REDIS_PASSWORD。
mvn clean package
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=qicai
```

应用默认监听 8082，管理员登录名为 `admin`，密码为初始化时设置的值。业务健康接口为 `/qicai/mobile/health`，具体路由以控制器映射为准。登录后台后录入品牌、商品、前置仓和运营账号。微信登录和支付通过外部配置及商户密钥文件启用，参数见 `.env.example` 和 `deploy/README.md`。

## 构建与验证

```bash
mvn clean package
```

产物为 `ruoyi-admin/target/ruoyi-admin.jar`。默认单元测试随构建执行，`*IT.java` 集成测试依赖隔离数据库与 Redis。`workspace-scripts/` 保存跨端回归源码，使用方式见该目录说明。

## 项目结构

```text
ruoyi-admin/        应用入口、采购配送业务控制器与服务、单元及集成测试
ruoyi-framework/    认证、会话、权限、Web 与安全配置
ruoyi-system/       用户、角色、菜单与系统配置持久化
ruoyi-common/       通用模型、工具、过滤器和上传处理
ruoyi-generator/    保留的代码生成模块源码
ruoyi-quartz/       保留的定时任务模块源码
sql/               基础表、历史演示种子及增量迁移
deploy/           初始化、部署、备份、告警与配置模板
workspace-scripts/ 跨端隔离验收与回归脚本
```

`ruoyi-generator`、`ruoyi-quartz` 的源码保留在目录中，当前运行入口的模块依赖以 POM 为准。基础框架沿用若依，版权和许可见 `LICENSE`。

## 简历描述示例

参与多端采购配送系统后端开发，构建企业客户审核、分端商品定价、订单支付与配送履约流程，通过 Spring Security 与业务数据边界实现管理员、销售员和客户的权限隔离。
