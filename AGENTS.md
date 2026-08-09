# Lucky5 项目协作指南

## 先读结论

- 所有命令默认在仓库根目录执行。
- 当前是 Spring Boot 单体后端 + Vue 3 管理端。根 `pom.xml` 实际只启用 `system`、`infra`；不要因为目录存在就假设 `member`、`pay`、`im` 已接入运行时。
- 本地标准运行方式是 `docker compose up -d --build`。Compose 会同时启动 MySQL、Redis、Java 服务和前端 Nginx。
- 先阅读目标目录附近代码并沿用既有分层与命名。不要顺手重排无关文件或全量格式化。
- 配置文件中存在演示用第三方密钥。不得在日志、提交说明或回复中复述；生产使用前必须改为外部注入并轮换。

## 项目地图

| 路径 | 作用 |
| --- | --- |
| `pom.xml` | Maven reactor；Java 17 编译目标，Spring Boot 3.5.x |
| `lucky5-dependencies/` | 统一依赖版本 BOM |
| `lucky5-framework/` | 通用 starter：Web、Security、MyBatis、Redis、租户、监控、测试等 |
| `lucky5-module-system/` | 用户、角色、菜单、租户、OAuth2、通知等系统能力 |
| `lucky5-module-infra/` | 文件、代码生成、定时任务、日志、配置、监控等基础设施 |
| `lucky5-server/` | Spring Boot 启动与聚合模块；入口为 `Lucky5ServerApplication` |
| `lucky5-ui/` | Vue 3 + TypeScript + Vite + Element Plus 管理端 |
| `sql/mysql/ruoyi-vue-pro.sql` | Compose 首次创建 MySQL volume 时执行的基线数据 |
| `compose.yaml` | 本地完整栈的唯一主入口 |

`lucky5-module-member/`、`lucky5-module-pay/`、`lucky5-module-im/` 虽有源码，但根 POM 和 server POM 默认已注释。启用一个模块时要同时检查：根 `<modules>`、`lucky5-server/pom.xml` 依赖、对应 SQL、前端菜单/API 以及外部中间件。

## 后端约定

- 包根为 `com.hnz.luck5`。业务模块通常按 `controller/admin|app`、`service`、`dal/dataobject|mysql`、`api`、`framework` 分层。
- Controller 返回 `CommonResult<T>`/`PageResult<T>`；请求 VO 使用 Jakarta Validation；权限沿用 `@PreAuthorize("@ss.hasPermission('module:resource:action')")`。
- Service 接口与 `*ServiceImpl` 分离；数据访问使用 MyBatis-Plus Mapper；对象转换优先沿用相邻代码中的 `BeanUtils` 或既有 MapStruct Convert，不新建平行转换风格。
- 数据对象通常继承审计/租户基类并使用逻辑删除。新增表和查询时必须核对 `tenant_id`、`deleted`、索引和权限编码。
- 错误码放在模块 `enums/ErrorCodeConstants` 体系中，业务校验沿用 `ServiceExceptionUtil.exception(...)`。
- 缩进 4 空格，类/方法命名遵循现有 Java 风格；Lombok、MapStruct 注解处理器由根 POM 统一配置。
- 默认配置在 `lucky5-server/src/main/resources/application.yaml`，环境覆盖在 `application-local.yaml`/`application-dev.yaml`。容器地址通过 Compose 命令行参数覆盖，不要把容器主机名写死进本地开发配置。

后端常用检查：

```bash
mvn -pl lucky5-server -am clean verify
mvn -pl lucky5-module-system -am -Dtest=AdminUserServiceImplTest test
```

宿主机没有 JDK/Maven 时，以 `docker compose build server` 作为可重复构建检查。`-DskipTests` 仅跳过执行测试，仍会编译测试源码；不要用它冒充测试通过。

## 前端约定

- 必须使用 pnpm；Node 要求 `>=20.19.0`，锁文件为 `lucky5-ui/pnpm-lock.yaml`。
- 页面位于 `src/views/<module>/...`，请求封装位于 `src/api/<module>/...`，共享组件/组合式函数分别位于 `src/components`、`src/hooks`。
- Vue SFC 沿用 `<script lang="ts" setup>`、Composition API 和 `defineOptions({ name: ... })`；全局 API/组件存在自动导入，先看相邻文件再添加显式 import。
- API 方法沿用模块对象或相邻文件的导出风格，并统一通过 `@/config/axios`；不要在页面直接创建 axios 实例。
- Prettier 规则：2 空格、单引号、无分号、100 列、无尾逗号。不要对整个旧目录做无关格式化。
- `vite.config.ts` 的构建输出取自对应 `.env.*`。Docker 使用 `.env.docker`，API 通过 Nginx 反代 `/admin-api/`，WebSocket 通过 `/infra/ws`。

前端常用检查：

```bash
cd lucky5-ui
pnpm install --frozen-lockfile
pnpm ts:check
pnpm lint
pnpm build:docker
```

注意：当前 Vite 配置把 `auto-imports.d.ts` 和 `auto-components.d.ts` 设置为仅在非构建模式生成，而这两个生成文件未纳入源码快照。全新环境直接运行 `pnpm ts:check` 会出现大量 `ref`、`computed`、`useMessage` 等未定义的历史基线错误。先运行一次 `pnpm dev` 生成声明文件后再做全量类型检查；容器/CI 的交付基准仍以 `pnpm build:docker` 为准。不要把这批基线错误误判为单个业务页面引入的问题。

项目历史文件不一定已全部满足最新版 lint；修改时至少检查受影响文件，并把基线问题与新增问题分开说明。

## Docker Compose 工作流

```bash
# 可选：自定义端口、数据库口令和 JVM 内存
cp .env.example .env

# 构建并启动完整栈
docker compose up -d --build

# 状态、日志与探活
docker compose ps
docker compose logs -f server
curl http://localhost:48080/v3/api-docs
curl -I http://localhost:8080/

# 停止但保留数据
docker compose down
```

- 默认入口：前端 `http://localhost:8080`，后端 `http://localhost:48080`，Swagger `http://localhost:48080/swagger-ui`。
- 当前依赖图没有注册 `/actuator/health`；Compose 用返回 OpenAPI 文档且正文包含 `"openapi"` 作为 Java 就绪探针，不能只凭全局异常处理返回的 HTTP 200 判断接口存在。
- 演示登录：租户“芋道源码”，账号 `admin`，密码 `admin123`；只用于本地初始化数据。
- MySQL 和 Redis 默认不映射宿主机端口，避免与本地服务冲突。需要排查时使用 `docker compose exec mysql mysql ...` 或 `docker compose exec redis redis-cli`。
- SQL 初始化只在空的 `mysql-data` volume 上执行。修改基线 SQL 不会自动迁移已有 volume；正式演进应添加版本化迁移，不能依赖删库重建。
- `docker compose down -v` 会永久删除本项目的 MySQL/Redis volume。除非用户明确要求重置数据，否则禁止执行。
- 若修改前端环境变量，必须重建 `frontend`；若修改 Java 源码或 Maven 依赖，必须重建 `server`。

## 修改与验证准则

1. 先确认功能属于当前启用模块；前端存在页面不代表后端接口已编译。
2. 数据库变更同时提供 schema/迁移、DO/Mapper、Service、Controller/VO 与测试，检查多租户和逻辑删除。
3. API 变更同步更新前端 `src/api` 调用和受影响页面；保持 `/admin-api`、`/app-api` 前缀约定。
4. 优先运行最小相关测试，再运行模块级测试/构建；交付时说明实际执行过的命令与未覆盖项。
5. 不提交 `target/`、`node_modules/`、`dist*`、`.env`、日志或真实凭据。
6. 当前源码快照可能不含 `.git`；有 Git 元数据时先检查 `git status`，保留用户已有改动。
