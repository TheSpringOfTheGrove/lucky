# 自动托下注明细 7 天清理任务

任务独立于应用运行。它每 10 分钟处理最多 100,000 条明细，每条 SQL 最多删除 2,000 条。每次运行以数据库时钟计算同一个 7 天截止点。已提交的批次在任务中断后仍然有效，下一轮会继续清理。

仅删除同时满足以下条件的 `lucky5_bet_item` 明细。

- 明细、注单和期号属于同一租户、同一老板，且均未逻辑删除。
- 注单类型为 `AUTO_PROXY`，交付方式为 `LOCAL_ONLY`，外盘状态为 `NOT_REQUIRED`。
- 注单已标记中奖或未中奖，注数已固化，明细已有结算结果。
- 注单和所属期开奖的结算时间都早于 7 天截止点。

清理后，旧自动托订单主记录和汇总仍可查询，逐注明细将被永久删除。InnoDB 会在表内复用空闲页。表文件实际磁盘大小需要单独观察。

## 独立数据库账号

将主机改为清理容器所在 Docker 网段，并保存生成的专用口令。

```sql
CREATE USER 'lucky5_retention'@'<清理容器所在网段>' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, DELETE ON `ruoyi-vue-pro`.lucky5_bet_item TO 'lucky5_retention'@'<清理容器所在网段>';
GRANT SELECT ON `ruoyi-vue-pro`.lucky5_order TO 'lucky5_retention'@'<清理容器所在网段>';
GRANT SELECT ON `ruoyi-vue-pro`.lucky5_issue TO 'lucky5_retention'@'<清理容器所在网段>';
```

保存 MySQL 客户端配置到 `/etc/lucky5/bet-item-retention.cnf`。确认容器 UID 65532 可读该文件，并限制宿主上的读取权限。

```ini
[client]
host=mysql
user=lucky5_retention
password=填入单独生成的口令
```

## 构建、预览与启用

从仓库根目录构建镜像并用默认的只读模式预览。

```sh
docker build -t lucky5-bet-item-retention:local deploy/production/bet-item-retention
docker run --rm --network lucky5-chen-guan-xi_default \
  --mount type=bind,src=/etc/lucky5/bet-item-retention.cnf,dst=/run/mysql.cnf,readonly \
  lucky5-bet-item-retention:local --defaults-file /run/mysql.cnf --database ruoyi-vue-pro
```

执行 `bash deploy/production/bet-item-retention/test-isolated.sh` 在一次性 MySQL 8.4 容器中校验预览、批次上限、清理范围、重复执行和配置表权限。

将 systemd service 和 timer 安装到 `/etc/systemd/system`。执行 `systemctl daemon-reload` 和 `systemctl enable --now lucky5-bet-item-retention.timer`。用 `journalctl -u lucky5-bet-item-retention.service` 查看删除计数，用 `systemctl disable --now lucky5-bet-item-retention.timer` 停止后续调度。
