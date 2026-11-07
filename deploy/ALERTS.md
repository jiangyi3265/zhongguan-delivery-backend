# 可配置告警与验收边界

本地已提供 `alert_runner.py` 和既有 health/backup service 的候选 drop-in。当前没有真实接收渠道，未在服务器安装，也没有宣称收件人已收到告警。用户确认先验收可配置通道与模拟测试。

## 行为

包装器执行原健康/备份命令，故障、间隔提醒和恢复事件保存在私有状态目录。待送队列以单个原子状态文件为准，发送失败保留原事件 ID，后续运行重试。HTTP 2xx 只标记 `http_accepted`，接收方需按事件 ID 去重；本方案是至少一次投递，不承诺恰好一次送达。错误事件只含检查名、时间、退出码、耗时和安全原因，不发送原命令输出、凭据或异常正文。

原检查失败仍返回非零；检查恢复但已配置渠道仍有未送事件时返回 2。未配置渠道时明确 `not_configured` 并保存待送事件，不能以检查恢复的退出 0 宣称真实通知通过。状态损坏、锁冲突、配置错误均非零，不自动删除待送状态。超时只清理本次拥有的检查进程组，无法确认清理时记录 `timeout_cleanup_unconfirmed`。

## 可重复本地验收

```powershell
$env:QICAI_EVIDENCE_DIR='tmp/remediation-20261017/alerts-final-20261018'
C:/Python314/python.exe scripts/alert-regression.py
```

必须使用未存放该结果的新证据目录。成功标准：退出 0，`alert-regression-results.json` 所有检查为 true。脚本使用临时文件、真实回环 HTTP 接收器及合成进程；文件写失败和进程清理失败是明确故障注入，其他请求实际发送到本地接收器。不能代表真实 systemd、Linux 权限/进程组或外部告警送达。

## 部署候选与恢复

先读取现有两个 unit，备份 `/etc/qicai` 相关脚本和原 unit/drop-in；确认当前目标仍为 `1.14.75.83` 及已有专用检查脚本。将本轮 `alert_runner.py` 安装为 `/etc/qicai/alert_runner.py`，root 所有、0600；状态目录 `/www/qicai/shared/alerts` 为 0700。复制配置样例为 `alerts-health.json` 和 `alerts-backup.json`，均0600；health 的 `command_timeout_seconds` 设置60，backup设置900。实际接收渠道未提供时 `webhook_url` 保持空串。

配置真实渠道时只接受 HTTPS。可选 `bearer_token_file` 必须为绝对路径、文件仅属主可读，值为原始 token；不得把 token 放进命令行或发布包。通用 JSON 格式需要接收服务或适配器支持，不能将任意供应商 Webhook 填入后假设它接受该格式。

将 `systemd/*/alerts.conf` 放入相应 `.service.d/`，运行 `systemd-analyze verify` 后 `daemon-reload`。先用独立检查名及独立状态目录做本机合成失败/恢复演练，再执行健康检查；真实备份任务仅在已确认窗口运行，不能为演练破坏真实备份。保存检查日志、退出码与事件 JSON，并在提供接收渠道后核对接收端和实际收件人。当前 Windows 验收没有执行这些 Linux 步骤。

drop-in 超时包括检查及最多十个待送事件的发送余量；socket timeout 不保证恶意慢响应的总墙钟截止时间。systemd 的最终限制可能杀死包装器，此时应保留待送状态以便下一轮重试。无接收渠道时历史事件可能积累，需要监控状态目录空间；不能未经核对丢弃故障事件。

恢复时只移回原 drop-in/脚本和执行 `daemon-reload`，保留状态目录及全部事件，不恢复旧数据库、不删除新备份。若只停用渠道，清空配置中的 `webhook_url` 并保留待送状态；之后重新启用可能投递旧事件，应根据事件时间判断。
