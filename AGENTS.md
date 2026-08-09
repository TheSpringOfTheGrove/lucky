# Lucky5 项目协作指南

## 先读结论

- 所有命令默认在仓库根目录执行。
- 当前是 Spring Boot 单体后端 + Vue 3 管理端。根 `pom.xml` 实际启用 `system`、`infra`、`lottery`；不要因为目录存在就假设 `member`、`pay`、`im` 已接入运行时。
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
| `lucky5-module-lottery/` | 从旧 Lucky5 迁入的多租户彩票业务：配置、会员、订单、开奖、返水、吃码、消息等 |
| `lucky5-server/` | Spring Boot 启动与聚合模块；入口为 `Lucky5ServerApplication` |
| `lucky5-ui/` | Vue 3 + TypeScript + Vite + Element Plus 管理端 |
| `sql/mysql/ruoyi-vue-pro.sql` | Compose 首次创建 MySQL volume 时执行的基线数据 |
| `sql/mysql/lucky5-business.sql` | Lucky5 业务表、租户 1 基线配置、菜单裁剪与业务权限 |
| `scripts/migrate-lucky5-postgres-to-mysql.ps1` | 旧 PostgreSQL 业务数据到当前 MySQL 的幂等迁移和计数校验 |
| `compose.yaml` | 本地完整栈的唯一主入口 |

`lucky5-module-member/`、`lucky5-module-pay/`、`lucky5-module-im/` 虽有源码，但根 POM 和 server POM 默认已注释。启用一个模块时要同时检查：根 `<modules>`、`lucky5-server/pom.xml` 依赖、对应 SQL、前端菜单/API 以及外部中间件。

## Lottery 业务边界

- 管理端 API 统一位于 `/admin-api/lottery/**`，公开房间 API 位于 `/app-api/lottery/room/**`。前者依赖登录租户上下文；后者必须显式传 `tenantId`，再由服务层写入租户上下文，禁止相信客户端提交的任意业务 `tenant_id`。
- 所有 `lucky5_*` 业务表都包含 `tenant_id`、审计字段与逻辑删除字段；DO 继承 `TenantBaseDO`，Mapper 不得添加绕开租户插件的普通查询。
- 前端集中在 `lucky5-ui/src/views/lottery`、`src/api/lottery` 和 `src/store/modules/lottery.ts`。多数管理页面从 `/lottery/bootstrap` 取得租户内快照，再调用细粒度写接口。
- 菜单基线只保留当前系统原有的“系统管理”“基础设施”，并加入：首页、配置管理、赔率设置、链接配置、预设订单管理、跟单列表、会员管理、会员操作管理、上下分审核、订单查询、历史记录、开奖历史记录、返水管理、吃码额度设定、吃码盈亏、消息记录。“快捷指令”是根级菜单，排序位于“系统管理”正上方，与“系统管理”平级。
- `sql/mysql/lucky5-business.sql` 的菜单 SQL 使用递归临时表保留系统/基础设施后代，再软删除其它旧菜单。改菜单时同时检查 `system_menu`、`system_role_menu`、`system_tenant_package_menu`，不要只改前端静态路由。
- 下注解析器 `LotteryBettingService` 保留旧系统定位、字现、倒、合、上奖、含、取值、重号、兄弟号和五位二定规则；修改时必须运行 `LotteryBettingServiceTest`，其中包含旧项目快捷指令语料回归。
- 市场连接的“测试/同步”目前只保存连接状态并明确返回“待接通”；旧项目未提供可验证的外部市场写入协议。不要伪造成功、余额或开奖同步，接入真实协议后再替换该边界。
- 全员流水清理和吃码记录清理是破坏性业务动作，服务端要求当前管理员密码复核；前端确认框不能代替后端校验。

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
mvn -pl lucky5-module-lottery test
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
- 已有 volume 需要补建/升级 Lucky5 结构时，可安全重复执行：`Get-Content -Raw sql/mysql/lucky5-business.sql | docker compose exec -T mysql mysql -uroot -p123456 ruoyi-vue-pro`。若 `.env` 修改了库名或口令，命令也要同步修改。
- `docker compose down -v` 会永久删除本项目的 MySQL/Redis volume。除非用户明确要求重置数据，否则禁止执行。
- 若修改前端环境变量，必须重建 `frontend`；若修改 Java 源码或 Maven 依赖，必须重建 `server`。

## 修改与验证准则

1. 先确认功能属于当前启用模块；前端存在页面不代表后端接口已编译。
2. 数据库变更同时提供 schema/迁移、DO/Mapper、Service、Controller/VO 与测试，检查多租户和逻辑删除。
3. API 变更同步更新前端 `src/api` 调用和受影响页面；保持 `/admin-api`、`/app-api` 前缀约定。
4. 优先运行最小相关测试，再运行模块级测试/构建；交付时说明实际执行过的命令与未覆盖项。
5. 不提交 `target/`、`node_modules/`、`dist*`、`.env`、日志或真实凭据。
6. 当前源码快照可能不含 `.git`；有 Git 元数据时先检查 `git status`，保留用户已有改动。

## 旧 Lucky5 数据迁移

旧仓库 `D:\Projects\lucky5` 只作为迁移源，禁止在迁移任务中写入。默认把全部旧业务数据归入当前租户 `1`，保留当前项目自己的用户、角色、权限、租户和基础设施数据：

```powershell
# 两套 Compose 的数据库容器都需已启动；脚本会应用业务 schema、覆盖目标业务表并逐表校验计数
.\scripts\migrate-lucky5-postgres-to-mysql.ps1 -TenantId 1

# 已确认 schema/menu 正确时可跳过 schema 重放
.\scripts\migrate-lucky5-postgres-to-mysql.ps1 -TenantId 1 -SkipSchema
```

- 迁移覆盖 22 个业务模型；脚本按外键安全顺序清空指定租户的目标业务行、批量写入，再核对每个源/目标表计数。失败时应查看最后一个表名，不要手工补一半后宣称完成。
- `User`、`Role`、`Permission` 不从旧系统迁移，因为当前系统管理承担账号、角色、菜单和租户隔离；旧业务 `Member` 是彩票会员，会迁入 `lucky5_member`，两者不是同一概念。
- 正式迁移快照（租户 1）：会员 7、上下分 8、订单 7、注单 385、开奖 419、期号 418、期号流转 1729、预设订单 59、快捷指令 10、跟单 61、操作日志 933、消息 17、吃码记录 7；配置类表和其它表由脚本输出为准。
- 脚本用 UTF-8 十六进制 SQL 字面量跨 PowerShell/Docker/MySQL 传中文，禁止改回管道直传的默认 Windows 编码。
