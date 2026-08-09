# Docker Compose 本地部署

完整栈的主配置已迁移到仓库根目录 `compose.yaml`，避免旧配置中的错误相对路径和不存在的 `lucky5-ui-admin` 目录。

在仓库根目录执行：

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f server
```

默认访问地址：

- 管理端：<http://localhost:8080>
- Java API：<http://localhost:48080>
- 后端就绪检查：<http://localhost:48080/v3/api-docs>

如需自定义参数，复制根目录 `.env.example` 为 `.env` 后修改。详细开发、测试和数据卷注意事项见根目录 `AGENTS.md`。

为兼容历史命令，本目录的 `docker-compose.yml` 使用 Compose `include` 引入根配置；新脚本应直接使用根目录入口。
