# 下注明细 7 天归档任务

本任务用独立容器运行。每天 04:20（北京时间）可处理最多 20,000 条明细记录，每批默认 100 条。每次运行以数据库时钟计算同一个 7 天截止点。

只处理同时符合以下条件的 `lucky5_bet_item` 明细：

- 明细、注单和期号属于同一租户、同一老板，且均未逻辑删除。
- 注单类型为 `AUTO_PROXY`，走本地老板模式，外盘状态为 `NOT_REQUIRED`。
- 注单已标记中奖或未中奖，注数已固化，明细已有结算结果。
- 注单和所属期开奖结算已完成满 7 天。

归档文件为可回放的 `.sql.gz`。任务写入、同步、回读校验归档后，按主键重新检查状态和整行校验值，再删除明细。部分失败可重跑。文件名尾部是解压后 SQL 的 SHA-256。老订单的已归档逐注明细可通过回放归档查询，订单主记录仍在数据库里。

## 独立数据库账号

以管理员创建专用账号。将示例中的主机匹配替换为归档容器所在 Docker 网段，并记录 SQL 返回的随机口令。

```sql
CREATE USER 'lucky5_retention'@'<归档容器所在网段>' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, DELETE ON `ruoyi-vue-pro`.lucky5_bet_item TO 'lucky5_retention'@'<归档容器所在网段>';
GRANT SELECT ON `ruoyi-vue-pro`.lucky5_order TO 'lucky5_retention'@'<归档容器所在网段>';
GRANT SELECT ON `ruoyi-vue-pro`.lucky5_issue TO 'lucky5_retention'@'<归档容器所在网段>';
```

使用 `SHOW GRANTS` 确认只有上述表权限。赔率、开关、链接、会员、资金流水和其他配置表由各自的数据库权限保护。

保存 MySQL 客户端配置到 `/etc/lucky5/bet-item-retention.cnf`。示例宿主使用 `lucky5-chen-guan-xi_default` 网络中的 MySQL 服务名。让容器用户 65532 能读该文件，限制其他用户的读取权限。

```ini
[client]
host=mysql
user=lucky5_retention
password=填入单独生成的口令
```

## 构建、预览与启用

将独立的归档存储挂载到 `/mnt/lucky5-retention-archive`，在其中创建 `bet-items` 目录并授权容器用户 65532 写入。服务只在该挂载点存在时运行。

从仓库根目录构建并用默认的只读模式查看候选数量：

```sh
docker build -t lucky5-bet-item-retention:local deploy/production/bet-item-retention
docker run --rm --network lucky5-chen-guan-xi_default \
  --mount type=bind,src=/etc/lucky5/bet-item-retention.cnf,dst=/run/mysql.cnf,readonly \
  lucky5-bet-item-retention:local --defaults-file /run/mysql.cnf --database ruoyi-vue-pro
```

包内的 `test-isolated.sh` 用一次性的 MySQL 8.4 实例验证预览、归档、恢复、损坏归档和配置权限。构建镜像后执行 `bash deploy/production/bet-item-retention/test-isolated.sh`。

安装同目录下的 systemd service 和 timer 后，执行 `systemctl daemon-reload` 和 `systemctl enable --now lucky5-bet-item-retention.timer` 启用。用 `journalctl -u lucky5-bet-item-retention.service` 查看每批归档和删除计数。若两者数量不一致，说明有记录在导出后变更或已被其他运行处理，仍保留其归档。

归档后 InnoDB 会在表内复用空闲页。表文件的实际磁盘大小需要单独观察。

## 恢复

先校验文件解压后的 SHA-256，再在隔离数据库用 MySQL 客户端回放。恢复 SQL 在事务中写入缺少的明细主键，遇到已有主键时保留现有记录。

```sh
set -euo pipefail
expected=$(basename "$archive" .sql.gz)
expected=${expected##*-}
actual=$(gzip -dc "$archive" | sha256sum | cut -d " " -f 1)
test "$actual" = "$expected"
gzip -dc "$archive" | mysql --defaults-file="$restore_cnf" --max-allowed-packet=128M "$database"
```
