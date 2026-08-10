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
- 所有 `lucky5_*` 业务表都包含 `tenant_id`、`user_id`、审计字段与逻辑删除字段；DO 继承 `LotteryUserBaseDO`。`LotteryUserDataPermissionRule` 对所有后台账号（包括 `super_admin`）都强制限定当前 `user_id`：超级管理员只能操作自己名下的旧业务数据，不能查看、审核、退码或结算老板数据；老板也只能操作自己的盘口数据。不要在 Mapper 或 Service 中绕开这条规则。
- 公开会员端没有后台登录上下文，查询时必须从会员记录取得归属 `user_id` 并显式限定。赔率和快捷指令使用“本人优先、本人完全没有时回退 `user_id=1` 超级管理员默认值”；本人第一次保存后即形成独立配置。其他业务配置不回退。
- 系统管理创建后台用户后会同步发布 `AdminUserCreatedMessage`，Lottery 侧由 `LotteryOwnerInitializationService` 在同一事务内把该用户初始化为独立老板盘口。租户 1 优先分配兼容角色代码 `crm_admin`（界面名称“老板账号”），其它租户回退 `tenant_admin`；初始化失败必须让账号创建整体回滚，不能留下半初始化用户。
- 老板初始化只补缺失数据、绝不覆盖已有配置：复制超级管理员的安全配置、赔率、开关定义、吃码额度和快捷指令，房间固定默认关闭且默认老板模式；玩家链接、设备号、盘口地址/账号/密码、盘口连接状态和第三方登录信息必须清空。快捷指令使用按租户+用户+模板 ID 生成的稳定 UUID，避免主键与超级管理员冲突。若当前租户没有模板，则使用代码和 `lucky5-business.sql` 中的内置基线。
- `lucky5_owner_initialization` 是老板初始化的一次性标记：账号创建事件或首次进入 Lucky5 首页只能通过 `INSERT IGNORE` 抢占一次自动初始化；自动或手动任一来源写入标记后，后续登录都不得再次自动初始化。超级管理员可在“系统管理 → 用户管理 → 更多 → 初始化老板配置”重复手动补缺，手动操作会增加执行次数但不能删除标记、重新开放自动初始化或覆盖已有业务数据。初始化各种列表时必须按开关 key、第三方 key、赔率 code、快捷指令内容逐项补缺，不能因为表里已有一条记录就跳过整类配置。
- 前端集中在 `lucky5-ui/src/views/lottery`、`src/api/lottery` 和 `src/store/modules/lottery.ts`。多数管理页面从 `/lottery/bootstrap` 取得租户内快照，再调用细粒度写接口。
- Lucky5 管理列表完成新增、编辑、设置、开关、审核或删除后，必须重新请求一次服务端数据再呈现结果，不能只修改当前前端行；统一写操作优先走 Store 的 `perform(...)`，由它在成功或失败后执行 `initialize(true)`。使用独立查询接口的订单、上下分等列表则调用各自的 `refresh*` 方法。
- 菜单基线只保留当前系统原有的“系统管理”“基础设施”，并加入：首页、配置管理、赔率设置、链接配置、预设订单管理、跟单列表、会员管理、会员操作管理、上下分审核、订单查询、历史记录、开奖历史记录、返水管理、吃码额度设定、吃码盈亏、消息记录。“快捷指令”是根级菜单，排序位于“系统管理”正上方，与“系统管理”平级。
- 首页仪表盘的三张统计卡片都必须可点击：“会员总数”和“在线会员总数”进入 `/lucky5/members` 会员管理列表，“未审核上分请求”进入 `/lucky5/amount-records` 上下分审核列表；禁止继续使用旧系统的 `/member`、`/amount-record` 路由。
- `sql/mysql/lucky5-business.sql` 的菜单 SQL 使用递归临时表保留系统/基础设施后代，再软删除其它旧菜单。改菜单时同时检查 `system_menu`、`system_role_menu`、`system_tenant_package_menu`，不要只改前端静态路由。
- 下注解析器 `LotteryBettingService` 保留旧系统定位、字现、倒、合、上奖、含、取值、重号、兄弟号和五位二定规则；修改时必须运行 `LotteryBettingServiceTest`，其中包含旧项目快捷指令语料回归。
- 会员链接按房间用途生成：群聊为 `/g/{openId}?tenantId=...`，私聊为 `/p/{openId}?tenantId=...`；两者都属于未登录白名单。`/room?tenantId=...&openId=...` 与 `/r/{openId}?tenantId=...` 只作为历史兼容入口，并按老板链接配置中的 `defaultRoomMode` 进入默认房间。不要照搬旧项目的 `/#/room`、`/#/r`，否则会落到后台首页。
- “链接配置”管理玩家房间入口，不再表示不同短链服务商：老板可选择仅群聊、仅私聊或两者同时开放；同时开放时必须指定默认入口，至少保留一种入口。关闭某类入口后，服务端也必须拒绝已发出的该类旧链接，不能只从会员链接弹窗隐藏。老板初始化默认同时开放群聊和私聊，默认入口为群聊。
- 群聊的共享边界是当前会员所属老板 `user_id`，不是整个租户：可共享同一老板真实玩家和自动托的 `BET/CHAT/SETTLEMENT` 内容，以及每期唯一的 `PAYOUT_SUMMARY`；其他玩家下注后同时公开脱敏机器人回执，保留期号尾号、原文、编号、注数和金额，但必须在服务端清除订单号、余额、上下分、退码入口和错误信息。私聊只返回当前玩家自己的消息、订单、上下分与完整机器人回复，不显示其他玩家、自动托或老板全盘派送汇总。公开会话从 `openId` 找到会员后再取得归属 `user_id`；`openId` 在租户内全局唯一，数据库唯一键必须为 `(tenant_id, open_id)`，禁止依赖客户端传老板编号。
- `lucky5_message.member_id` 是房间消息身份的稳定字段，新消息必须写入；存量消息可用同老板下的会员名回填，只有无法匹配的占位审计消息允许为空。群聊页面按 `own` 区分右侧本人消息和左侧其他玩家消息；真实玩家和自动托的下注原文、中奖结算机器人回复都要按相同规则公开，自动托必须使用普通玩家的昵称、头像和消息样式，不得暴露“托”标签，但虚拟派彩仍不进入本次总派送。私聊不得显示其他玩家或自动托。不要根据会员当前类型反推历史订单类型。
- 房间下注、消息、上下分申请和退码入口都必须由公开 Service 方法建立事务，再在 `TenantUtils.execute(...)` 内完成会员凭据解析和写入；不要依赖同类内部自调用上的 `@Transactional`。快选预览使用独立的 `LotteryRoomReqVO.PreviewBet`，只要求会员凭据和 `content`；禁止复用正式下注 `Bet` 导致无意义地校验 `period`。
- 房间会话的消息时间字段必须返回 `createdAt`（后台列表兼容字段 `time` 可同时保留）；前端排序必须使用稳定时间和固定消息 ID。轮询刷新只允许在用户原本停留于底部时自动跟随，不能把正在查看历史消息的用户强制拉到底部。会话接口可返回最近 20 期供开奖记录表格使用，但聊天区首次进入只渲染最新一期，随后仅在检测到新期号时追加一次；禁止把整批历史开奖逐条回放成机器人消息。开奖记录中的五位号码必须先归一化，再按五个独立元素和固定 `gap` 渲染，禁止依赖普通/全角空格拼接号码。刮牌入口固定靠右并只允许纵向拖动，位置保存在浏览器本地且窗口变化时限制在可视区域内。
- 会员房间顶部固定显示当前老板房间名、玩家昵称、可用积分、完整当前期号、开盘/封盘/开奖确认/开奖异常状态和封盘倒计时，不显示“本期多少单、多少分”的订单统计；开奖记录栏只显示最新一期的“完整期号 + 开奖时间 + 五位号码球”，点击整行展开近 10 期列表，列表也必须显示完整期号、时间和号码，禁止在这些位置截成后三位造成误解。顶部不得挤压或覆盖聊天与输入区，刮牌按钮的纵向拖动下限必须避开顶部信息栏。倒计时以接口 `serverTime + remainingSeconds` 校准并每秒显示，禁止只靠页面打开后的本地累计计时。
- 公开房间的页面和所有输入入口不随 `roomOpen` 隐藏：虚拟键盘、快选、快捷指令、聊天输入和符合 `openCancel` 的退码入口始终按自身配置展示。`roomOpen=false` 时会员仍可进入、读取会话、聊天、查询余额和按退码规则撤销未开奖订单；只有下注和上下分申请被拒绝。网页群下注/上下分还要检查 `pullEnable`。停盘业务指令要保存会员消息并生成 `ROOM_CLOSED` 机器人回复“@会员\n当前未开盘”，不能只返回顶部 Toast；直接下注/上下分 API 仍保留后端守卫。
- `LotteryRoomMessagePolicy` 先识别 `查`/`余额`、`上[分]金额`、`下[分]金额`、`退[码]订单号`和有效下注，其余无法被下注解析器识别的文本按 `CHAT` 保存。`CHAT` 不生成机器人“已处理”回执；带 `externalId` 的房间消息仍按会员归属用户幂等。成功下注回复包含期号尾号、期内编号、注数、金额、余额与“点击退码”。会员端只返回最近 60 条 `OPEN/CLOSED` 期号流转，禁止把 `DRAW_PENDING` 等内部状态渲染成重复的“停止-上课”。
- 机器人文本统一由后端 `LotteryRobotReplyTemplate` 和会员房间 `replyTemplates.ts` 维护，禁止在 Controller/页面中新增同义拼接。参考协议包括：`^^★★★开始-答题★★★`、`^^★★★停止-上课★★★`、`^^--| 期号尾三位期开奖结果-号码`、“本期成功订单”、下注的挂牌时间/户型审核/编号/套内/套外/面积/点击退码，以及中奖后的入住/房费/当前面积和独立的本次总派送。审核成功标记必须使用真正的勾号 `✓`（U+2713），禁止使用数学根号 `√`；读取历史回复时兼容转换。当前面积只表示派奖后的最新余额，不再显示“中介费”或派奖前余额。每期结算必须生成且只生成一条 `PAYOUT_SUMMARY` 群聊系统消息，无人中奖时也发送 `【本次总派送】：0`；金额只统计真实玩家实际入账派彩，排除自动托，私聊不得显示老板全盘总额。开奖播报禁止使用“房间号”称呼期号或开奖号码，以免被误解为老板房间唯一编号。文本金额去掉无意义的 `.00`，API 金额仍保留数值精度。上下分审核完成后必须回写原申请消息为标准通过/拒绝回复。
- 赔率设置页必须服从当前老板配置的 `playType`：`0`（普通）只显示 `regex4x` 至 `regex1d` 等普通赔率，隐藏 `regexlh/regexh`；`1`（龙虎和）只显示 `regexlh/regexh`；`2`（普通+龙虎和）显示全部。页面隐藏只影响展示，保存时必须保留隐藏玩法已有赔率，禁止把它们删除或重置为零，确保切换玩法后原配置仍可恢复。
- 龙虎和下注必须从当前老板赔率配置取值：龙、虎共用 `regexlh`，和使用 `regexh`，并在生成订单前统一校验启用状态和最小/最大限额；禁止回退到写死的 `1.95`、`8.8` 等赔率。订单明细继续保存下注当时的赔率快照，后续修改配置不得重算历史订单。
- 配置运行模式只有两种安全语义：`bossMode=true` 是老板模式，真实玩家订单全部留在本地并写 `LOCAL_ONLY/NOT_REQUIRED`；`bossMode=false` 是本地模拟网盘测试模式。模拟模式使用每个老板独立的 `lucky5_simulated_market_account` 和默认 100000.00 虚拟余额，下注、退码、结算只更新本地模拟账户及 `lucky5_market_route_item` 路由快照。当前代码没有任何真实盘口下单、退单或派奖写适配器，所有配置/API 固定返回 `realMarketWritesEnabled=false`；未取得用户新的明确授权、单独的环境开关和经过验证的写协议前，严禁把模拟状态改成真实成功或调用真钱接口。
- 模拟网盘余额在下注事务提交时立即扣减，不得等到结算才扣；结算只补入中奖派彩，退码才返还模拟本金。配置页通过 `/lottery/config/sync` 每 2 秒读取轻量模拟账户快照并合并余额、累计统计及最近路由，禁止为了刷新余额重复加载整套 bootstrap；此轮询只允许在 `mode=SIMULATED` 时运行，老板模式不得借此高频访问真实盘口。
- 配置管理允许保存不完整的只读盘口凭据；老板模式和模拟网盘模式的房间启动都不依赖真实盘口账号。模拟模式即使保留了历史网址、账户和密文，也只能使用本地模拟余额，严禁据此建立真实写连接。
- 会员房间退码只能操作自己的未开奖真实订单且要求 `openCancel`；老板后台可以直接退自己盘口的所有未开奖订单（包括自动托），不受会员端 `openCancel` 开关影响。所有退码都必须在事务内恢复对应会员或虚拟托的余额和总下注并同步消息状态；自动托不能从房间端退码。结算按用户+期号幂等，同开奖结果重复调用直接返回 `alreadySettled`，不同结果拒绝；逐注计算派彩、更新订单/会员/消息，并生成中奖结算消息。
- 会员余额的每一次实际变动都必须同时写入不可变资金流水 `lucky5_balance_ledger`。简单上/下分和返水统一调用 `LotteryBalanceLedgerService.change` 做余额非负校验与 `version` 乐观锁；下注、退码、派奖已有复合字段更新时，在同一事务内调用 `recordAppliedChange` 留痕。业务类型和业务 ID 组成幂等键，禁止先改余额、后补流水。
- 待审核上下分必须先用 `status=待审核` 条件更新抢占审核权，再变更余额；并发重复审核只能有一个成功。即时上下分和审核上下分都只接受“上分”“下分”，不能把未知类型默认为入账。后台直接编辑会员余额会记录“人工调整”，新建会员非零余额会记录“初始积分”。上下分审核页使用独立的 `/lottery/amount-records` 查询接口并保持短轮询；订单查询和历史记录页使用独立的 `/lottery/orders` 查询接口。订单查询页只在首次打开和用户点击“搜索”时加载，禁止定时自动刷新；当前订单入口只有网页，来源列统一显示“网页”，盘口状态和展开列不展示，操作列对所有可退的未开奖订单（含自动托）显示“退码”，成功后立即刷新列表。订单类型筛选必须覆盖该类型的全部状态，不得附加状态条件；类型显示、筛选和详情共用同一归一化规则：`orderType=AUTO_PROXY` 或 `autoProxy=true` 才是自动托，其余（含历史空值）统一作为 `PLAYER` 真实玩家。订单表必须用订单 ID 作为 `row-key`，切换类型时重建表格，禁止复用其他状态或类型订单的旧行内容。订单状态在桌面和手机端统一用颜色区分：未开奖橙色、已中奖红色、未中奖绿色、已退码灰色、异常红色。点击订单文本用弹窗查看订单概况与玩法明细；概况显示该期开奖号码（未开奖显示“待开奖”）而不是订单状态，明细中奖项必须排在最前并用红色标识，未中奖项用绿色标识，待开奖保持中性色。手机端详情使用接近全屏的单列概况，仅保留期号、会员、总金额和开奖号码，隐藏类型、来源以及明细中的赔率、派彩，结果列必须保持可见；桌面端继续显示完整字段。后续接入真实平台时再扩展来源。历史记录页仍可在停留期间短轮询并在离开页面时停止。
- 会员操作管理只展示“会员昵称、内容、创建时间”，不要额外增加“操作人”列。有具体会员的日志显示 `OperationLogDO.member`；老板执行配置/开关等没有具体会员的后台操作，以及定时任务、自动结算等系统日志，会员昵称统一显示 `【系统】`。`operator` 仍用于人工/系统筛选和审计，但不直接占用表格列。
- 消息列表是房间聊天审计流，只读且不提供后台手工录入入口；必须收集当前老板房间内的真实玩家、自动托和机器人消息，只排除开奖结果播报，不能套用“业务报表排除自动托”的规则。顶部只保留“期数、内容、昵称”查询，列表只显示“发送人、内容、创建时间”。同一条 `MessageDO` 的玩家原文和机器人 `reply` 必须拆成独立行，时间倒序中机器人回复紧跟对应玩家消息并保留原始换行；按玩家昵称查询时要同时返回该玩家对应的机器人回复。历史查询使用独立 `/lottery/messages` 接口服务端分页，默认每页 10 条，不得依赖 bootstrap 的历史截断。手机端使用分页卡片显示发送人、时间、完整内容和期号，不依赖横向滚动。
- 返水只统计已结算订单，且只统计会员 `flowClearedAt` 之后的订单和返水记录；已发放返水按对应普通/龙虎流水基数扣减，不能重复发放。`dragonTigerSeparateRebate=false` 时龙虎按普通返水率计算，开启后才使用龙虎独立返水率。发放返水必须同时生成 `RebateRecordDO`、已通过的“返水”上下分记录和资金流水。拉手身份用会员 `tag=拉手` 表示，玩家的 `partner` 只能指向同一老板名下的有效拉手且不能指向自己；取消拉手时必须解除所有指向他的下级关系。`partnerNormalRate/partnerLhhRate` 是从该玩家待返水流水中额外计算并发给所属拉手的比例；界面始终允许预设这两个比例，未绑定拉手时不得置灰或清零，只在实际发放时校验有效所属拉手并计算。发放时把玩家返水和拉手返水分别记入各自余额、上下分记录及资金流水，同时在同一条源玩家 `RebateRecordDO` 中保存拉手会员和分项金额，防止重复计算。自动托不参与玩家或拉手返水。
- 同一老板的手工返水和自动返水通过该老板 `lucky5_system_state` 行锁串行化；自动结算开启返水时要在派奖前取得同一把锁。不要改成 JVM 本地锁，因为多实例部署无法保证幂等。
- 预设订单没有启用/停用状态：新增或编辑时必须通过当前有效赔率解析，展开后最多 10000 注；历史迁移数据中的 `enabled` 仅为兼容字段，自动托不得按它过滤。期号提交为 `OPEN` 后才异步触发自动托；每个自动托会员从当前老板所有可解析的预设指令中随机选择且每期只下注一条，不可解析的历史指令直接排除。每次下注使用独立新事务，会员+期号共用一个长度不超过 100 的幂等键，同期并发事件最多形成一笔订单；余额、房间、拉单开关、赔率和限额仍统一经过 `placeBetInternal` 校验。
- 自动托是老板名下的虚拟会员，内部身份固定为 `memberType=BOT`；会员管理页沿用旧系统的 `autoProxy`“是/否”开关切换真实会员和自动托，打开时同步启用 `autoBetEnabled`，关闭时同步停用。`eatEnabled` 与自动托开关必须独立保存、独立显示，切换自动托时不能清空吃码设置；但 `memberType=BOT` 期间即使“吃”为是，也只保留设置而不参与吃码统计，切回真实会员后该设置重新生效。开盘事件必须先写入 `lucky5_auto_proxy_execution`，按会员+期号唯一键延迟 5～30 秒执行；任务采用 `SCHEDULED/RUNNING/SUCCESS/FAILED/SKIPPED` 状态和版本号抢占，服务重启后要恢复超时任务，禁止只依赖 JVM 定时器。
- 自动托余额不足时按 `max(autoTopUpAmount, 本次所需金额-当前余额)` 自动上分；上分记录直接写为“已通过”、生成不可变资金流水和标准机器人通过通知，不进入老板的上下分审核列表。自动托下分同样即时通过，不需要老板审核。自动托资金、返水、吃码、真实会员总数/余额/盈亏、上下分统计和历史业务报表全部排除；后台业务界面通常仅在“会员管理”和“订单查询”保留其身份与订单，但“消息列表”作为房间聊天审计必须保留其房间消息和机器人回复。群聊房间公开其下注原文且外观与真实玩家一致，私聊房间不显示其他自动托。内部执行表和资金流水仅用于链路运行与审计。
- 自动托订单固定写 `orderType=AUTO_PROXY`、`deliveryMode=LOCAL_ONLY`、`marketStatus=NOT_REQUIRED`，无论老板模式还是盘口模式都绝不能进入 `LotteryMarketSyncService` 或第三方网盘。虚拟订单仍按开奖号码正常结算并改变虚拟积分，但结算汇总、真实资金、返水和吃码只计算 `orderType=PLAYER`；老板可在订单查询中退未开奖的自动托订单，退码只恢复虚拟积分和虚拟托总下注，不能使其参与真实经营数据。
- `orderType` 是订单创建时的不可变身份快照：会员后来在真实玩家和自动托之间切换，只影响后续新订单，绝不能按会员当前类型重算历史订单。迁移旧数据时只允许根据订单自身 `source=自动托` 或关联消息的 `externalId=auto-proxy:*` 识别虚拟订单，其余历史订单保持 `PLAYER`。
- `channel=跟` 当前表示跟单来源历史：成功下注后写入 `lucky5_follow_order`，不是玩家之间的连续自动复制规则。`跟`/`停止` 的持续期数、金额缩放和 `delayOrder` 延迟规则没有可靠协议前不得猜测实现；跟单历史的订单文本字段为 2000 字，避免长指令使下注事务回滚。
- 吃码在老板模式仍只是本地经营标记；在模拟网盘模式会按“当前老板 + 当前期号 + 玩法”执行先进先出的资金路由。真实玩家未开启“吃”时整单进入模拟网盘；开启后，配置中的吃码额度是该玩法当期所有吃码玩家共享的老板本地承接上限，额度内写 `LOCAL_EAT`，超出部分写 `SIMULATED_MARKET`，跨额度订单写 `MIXED_SIMULATED`。额度为 0 或当前没有对应额度字段的玩法不得解释为无限吃入，必须全部进入模拟网盘；自动托始终 `LOCAL_ONLY`，绝不能进入模拟账户。模拟余额不足要让整笔玩家扣款、订单和路由事务一起回滚。退码按路由快照退回模拟本金，派奖只把模拟份额派彩计入模拟余额；吃码报表对有新路由快照的订单只统计本地吃入及其派彩，历史无快照订单继续按原整单口径兼容。
- 第三方机器人只允许 `blueWhale`、`fish`、`wechat` 三个适配器键。后台绑定配置只能进入“待验证”，不能伪造“已登录”；微信、飞鱼、蓝鲸来源下注仅在真实适配器完成连接并写入“已登录”后放行，否则返回 `INTEGRATION_NOT_READY`。
- 盘口只读协议已迁移到 `Wa55MarketClient`：使用浏览器 User-Agent 登录 `/Member/DoLogin`，从 `GetMemberPrint`、`GetCurrentPeriodStatus`、`GetDrawNoTable` 读取余额、期号和开奖。该客户端只允许读取连接、真实余额、期号和开奖号码，禁止向其中增加下注等写请求；测试下注统一切换到本地模拟网盘。盘口返回字段存在多种大小写/旧版别名，特别是 `last_seconds`、`system_db_now` 和五个号码字段；修改解析时要保留前导零并运行 `Wa55MarketClientTest`。
- 盘口密码沿用旧项目 `v1:iv:tag:ciphertext` AES-256-GCM 格式，由 `MARKET_CREDENTIAL_KEY` 外部注入；保存空密码或 `********` 必须保留现有密文，禁止再写 `externalized` 等占位值。Compose 的默认密钥仅供本地兼容迁移数据，生产环境必须覆盖。
- 幸运5期号和开奖号码是系统级共享数据。`LotteryMarketSyncService` 只从 `lottery.draw-source.tenant-id/user-id` 指定的只读账号（Compose 默认租户 1、用户 1）获取一次开奖快照，再在各自 `TenantUtils.execute(...)` 中把同一期号、倒计时和号码分发给所有老板的独立 `lucky5_issue/lucky5_draw` 数据；不允许按老板盘口返回不同开奖结果，也不允许找不到指定源时自动换用名称相似的公开彩种。普通老板自己的盘口账号只同步本人连接、账号和只读余额，测试下注统一使用本人独立的本地模拟账户。
- 共享开奖同步不得修改任何老板的 `lucky5_system_state.room_open`；老板启动开关与全局期号状态是两个维度。所有老板可收到相同 `OPEN/CLOSED/DRAW_*` 期号；群聊下注/上下分要求本人手动启动且 `pullEnable` 允许，私聊下注/上下分只要求本人手动启动，旧的第三方“私聊”通道仍按 `privateMode/privateChat` 独立校验。开奖快照必须先为所有老板提交事务，再在独立阶段按 `tenant_id + user_id` 触发幂等结算，避免新老板首次补历史时读取未提交的 `DRAWN` 状态。
- `OPEN` 快照以 `serverTime + remainingSeconds` 为有效截止时间，允许最多 90 秒轮询抖动；超过后仍未收到新快照必须在公开会话映射为 `SOURCE_STALE`，显示“开奖源异常”、倒计时归零并拒绝所有直接下注、消息下注和自动托下注。禁止根据五分钟周期在本地猜测并生成新期号。HTTPS 证书或域名失效时必须保留证书校验并提示更新有效盘口网址，严禁用全局 trust-all/关闭主机名校验掩盖线路故障。
- 自动开奖只接受开奖 API 连续两次、间隔至少 5 秒返回的同一个非 `00000` 五位号码；状态依次为 `DRAW_ABNORMAL|DRAW_PENDING -> DRAWN -> SETTLING -> SETTLED`。非法号码和 `00000` 必须保留异常状态并停止结算、派奖；结算过程中或结算后的号码冲突不得覆盖原结果，必须留待人工复核。
- `lucky5_issue.result` 保存紧凑号码（如 `12345`），`lucky5_draw.result` 保存逗号分隔号码（如 `1,2,3,4,5`），不要混用。人工开奖必须填写原因并写入期号流转和操作日志；大小单双、单双、龙虎只能从五位号码推导，不能信任客户端覆盖值。自动和人工结算都必须按 `tenant_id + user_id + period` 幂等执行。
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

## Lucky5 移动端约定

- Lucky5 后台业务页以 `768px` 作为移动端断点；桌面端保留信息完整的表格，移动端禁止依赖横向滚动查看主要业务数据。
- 通用分页列表使用 `PaginatedTable`：桌面列继续放在默认插槽，移动端必须提供 `#mobile` 卡片插槽。卡片优先展示业务主键、金额/状态、核心文本和必要操作，次要审计字段可以省略或收进详情。
- 移动卡片沿用全局 `lucky-mobile-card__title/content/meta/actions` 结构；操作按钮必须换行，长指令、备注和错误信息必须允许断行，不能撑宽页面。
- 页面筛选区统一使用 `lucky-toolbar`、`lucky-toolbar__filters`，移动端输入框和下拉框占满可用宽度；表单使用 `lucky-original-form`，在移动端把标签和控件改为上下单列。
- Element Plus 业务弹窗统一增加 `lucky-dialog`，移动端宽度限制为视口减 16px，正文区域可纵向滚动。详情中的 `el-descriptions` 应按移动端切成单列，非关键表格列可以隐藏。
- 后台顶部工具栏在移动端只保留菜单折叠、租户选择、项目设置和用户入口；搜索、全屏、字号、语言、消息等次要入口留在桌面端，避免挤压内容。
- `room` 是玩家端专用页面，继续维护自己的手机布局，不复用后台列表卡片规则；修改后台通用样式时必须确认不会覆盖房间顶部、聊天区、输入区和刮牌按钮。
- 移动端验收至少使用 `375x667` 视口逐个打开 Lucky5 全部菜单，确认 `documentElement.scrollWidth === innerWidth`、`.lucky-page` 无横向溢出、列表显示移动卡片且桌面表格隐藏，并抽查新增/编辑/详情弹窗。

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
- 已有 volume 需要补建/升级 Lucky5 结构时，可安全重复执行：`docker compose exec -T mysql sh -lc 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /docker-entrypoint-initdb.d/02-lucky5-business.sql'`。必须从容器内按二进制 UTF-8 读取挂载文件；禁止用 Windows PowerShell 的 `Get-Content | mysql` 管道重放含中文 SQL，否则菜单名会被写成问号。
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

旧仓库 `D:\Projects\lucky5` 只作为迁移源，禁止在迁移任务中写入。默认把全部旧业务数据归入当前租户 `1`、后台用户 `1`（超级管理员）；保留当前项目自己的用户、角色、权限、租户和基础设施数据：

```powershell
# 两套 Compose 的数据库容器都需已启动；脚本会应用业务 schema、覆盖目标业务表并逐表校验计数
.\scripts\migrate-lucky5-postgres-to-mysql.ps1 -TenantId 1 -OwnerUserId 1

# 已确认 schema/menu 正确时可跳过 schema 重放
.\scripts\migrate-lucky5-postgres-to-mysql.ps1 -TenantId 1 -SkipSchema
```

- 迁移覆盖 22 个业务模型；脚本按外键安全顺序清空指定租户的目标业务行、批量写入，再核对每个源/目标表计数。失败时应查看最后一个表名，不要手工补一半后宣称完成。
- `User`、`Role`、`Permission` 不从旧系统迁移，因为当前系统管理承担账号、角色、菜单和租户隔离；旧业务 `Member` 是彩票会员，会迁入 `lucky5_member`，两者不是同一概念。
- 正式迁移快照（租户 1）：会员 7、上下分 8、订单 7、注单 385、开奖 419、期号 418、期号流转 1729、预设订单 59、快捷指令 10、跟单 61、操作日志 933、消息 17、吃码记录 7；配置类表和其它表由脚本输出为准。
- 脚本用 UTF-8 十六进制 SQL 字面量跨 PowerShell/Docker/MySQL 传中文，禁止改回管道直传的默认 Windows 编码。
