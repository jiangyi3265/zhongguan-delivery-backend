# 跨端回归脚本

这里保存项目已有的验收及回归源代码。部分脚本生成隔离数据库、账号、订单与图片夹具，执行结果写入工作区 `tmp/`。

历史脚本按原始工作区目录定位三端。使用下列目录布局克隆三个仓库，并把本目录中的脚本复制到工作区根目录的 `scripts/`，把后端 `deploy/` 复制到工作区根目录的 `deploy/`。

```text
workspace/
  RuoYi-Vue/          zhongguan-delivery-backend
  RuoYi-Vue3/         zhongguan-delivery-admin
  三端配送小程序/      zhongguan-delivery-app
  scripts/           本目录脚本副本
  deploy/            后端 deploy 副本
```

安装 `acceptance-requirements.txt` 中 Python 依赖，按三端 README 安装构建环境。浏览器脚本通过 `playwright-runtime.cjs` 查找 Playwright，可通过 `QICAI_PLAYWRIGHT_DIR` 指向安装目录。隔离全链路工具 `acceptance.py` 还需要 MySQL、Redis 可执行文件，其参数和环境变量以脚本定义为准。

纯源码回归可在上述 workspace 目录执行。

```bash
node scripts/session-client-regression.mjs
node scripts/admin-session-client-regression.mjs
node scripts/storefront-client-regression.mjs
```

完整隔离验收入口为 `python scripts/acceptance.py --local`。真实微信登录、支付和服务器部署需要对应环境与外部凭据。
