# 二次开发与现有服务器升级

三个仓库共同组成采购配送系统。新开发人员可以克隆源码、安装锁定依赖，在本地完成修改和构建，再将发布产物部署到现有服务器。

## 获取代码

下面的目录名称与跨端回归脚本一致。

```bash
mkdir zhongguan-workspace
cd zhongguan-workspace
git clone https://github.com/jiangyi3265/zhongguan-delivery-backend.git RuoYi-Vue
git clone https://github.com/jiangyi3265/zhongguan-delivery-admin.git RuoYi-Vue3
git clone https://github.com/jiangyi3265/zhongguan-delivery-app.git 三端配送小程序
```

在各仓库创建功能分支，修改完成后提交并推送。再次接手时，在相应仓库执行 `git pull --ff-only` 获取已合并到当前分支的代码。

## 本地开发与构建

准备 JDK 17、Maven 3.9、Node.js 24、pnpm 11.2.2。后端本地运行还需要 MySQL 8、Redis，以及 `.env.example` 中列出的环境配置。

1. 后端按根目录 README 在本地空库完成初始化，运行 `mvn clean package`，再启动 `ruoyi-admin/target/ruoyi-admin.jar`。
2. 后台执行 `pnpm install --frozen-lockfile`，复制开发、生产环境模板，执行 `pnpm dev --port 5173` 开发，执行 `pnpm build:prod` 构建。
3. 用户端执行 `pnpm install --frozen-lockfile`，执行 `pnpm dev:h5` 开发。使用实际 API 地址分别执行 `pnpm build:h5` 和 `pnpm build:mp-weixin`。

可复用的自动化测试包含后端 `src/test`、用户端 `scripts` 以及后端仓库的 `workspace-scripts`。跨端脚本的工作区布局和依赖见 `workspace-scripts/README.md`。

## 运行数据与配置交接

GitHub 保存源码、静态界面资源、依赖锁文件、建表及迁移脚本、配置模板和部署工具。

现有业务继续依赖服务器上的以下内容。

| 内容 | 用途 | 当前部署约定 |
| --- | --- | --- |
| MySQL 业务库 | 商品、品牌、客户、订单、支付、配送及仓点等记录 | `qicai_delivery`，服务 `qicai-mysql` |
| 共享上传目录 | 后台上传的商品图、品牌图、企业资料、签收凭证 | `/www/qicai/shared/uploads` |
| 外部环境及服务配置 | 数据库连接、Redis、会话签名及应用配置 | `/etc/qicai/app.env`、`/etc/qicai/application-server.yml` |
| 微信凭据文件 | 微信登录、支付请求签名、验签及回调解密 | 由 `QICAI_WECHAT_*` 文件路径变量指定 |
| Nginx 与 HTTPS 配置 | 域名、静态资源、API 代理和证书 | 按服务器实际站点配置交接 |
| 小程序平台权限 | AppID、合法域名、隐私配置和版本上传 | 由小程序管理员维护 |

以上路径来自项目部署约定，接手时通过服务配置核对实际路径。开发环境连接本地隔离库，现有服务器升级时沿用已配置的业务库、上传目录和商户凭据。

## 已有服务器发布步骤

1. 记录当前版本目录、三个仓库的提交号及实际运行配置，备份数据库、上传文件和私有配置，并验证恢复所需文件齐全。
2. 从指定提交构建后端 JAR、后台静态资源、H5 和小程序包，将服务端产物放到新的发布目录。
3. 涉及表结构变更时，先在备份恢复出的隔离环境验证对应增量迁移，再安排线上迁移。`deploy/README.md` 列出了现有迁移顺序。首次建库脚本适用于空数据库，已有业务库使用增量脚本。
4. 新版本继续连接现有共享数据目录和外部配置，切换发布目录指针，重启应用并校验 Nginx 配置。
5. 检查 `/qicai/mobile/health` 的应用、数据库、schema 和 Redis 状态，再回归登录、品牌及商品展示、B/C 定价、下单、支付查询、后台订单和配送流程。
6. 小程序导入新构建包，核对 AppID 和 API 域名，上传体验版并完成真机验证后提交审核。

回退应用版本前核对数据库兼容性，支付、退款和履约记录按最新业务状态保留。备份与版本切换工具分别为 `backup.py`、`release_switch.py`，具体部署参数由服务器环境提供。

## 迁移到新服务器

先准备运行环境，再恢复业务数据库和共享上传文件，配置外部凭据、域名与 HTTPS，部署构建产物并进行完整回归。业务恢复需要数据库和上传目录配套，代码仓库中的建表脚本用于创建初始结构。
