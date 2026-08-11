package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.framework.security.core.service.SecurityFrameworkService;
import com.hnz.luck5.framework.security.core.util.SecurityFrameworkUtils;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.controller.app.vo.LotteryRoomReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.*;
import com.hnz.luck5.module.lottery.dal.mysql.*;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.*;

@Service
@Validated
public class LotteryServiceImpl implements LotteryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Long DEFAULT_OWNER_USER_ID = 1L;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> INTEGRATION_KEYS = Set.of("blueWhale", "fish", "wechat");
    private static final String MEMBER_REAL = "REAL";
    private static final String MEMBER_BOT = "BOT";
    private static final String TYPE_PLAYER = "PLAYER";
    private static final String TYPE_AUTO_PROXY = "AUTO_PROXY";
    private static final String COMMAND_SETTLEMENT = "SETTLEMENT";
    private static final String COMMAND_PAYOUT_SUMMARY = "PAYOUT_SUMMARY";
    private static final Set<String> DRAW_RESULT_COMMANDS = Set.of("DRAW", "DRAW_RESULT", "LOTTERY_RESULT");
    private static final String ROOM_MODE_GROUP = "GROUP";
    private static final String ROOM_MODE_PRIVATE = "PRIVATE";
    private static final String CHANNEL_WEB_GROUP = "网页群";
    private static final String CHANNEL_WEB_PRIVATE = "网页私聊";
    private static final Duration MEMBER_HEARTBEAT_WRITE_INTERVAL = Duration.ofSeconds(15);
    private static final Duration MEMBER_ONLINE_TIMEOUT = Duration.ofSeconds(90);

    private record BetResult(Long messageId, String orderId, String member, String period, BigDecimal amount,
                             BigDecimal balance, int itemCount, int periodSequence,
                             List<LotteryBettingService.ParsedBet> items, String status, boolean duplicate) {
    }

    private record CancelResult(String id, String status, BigDecimal refunded) {
    }

    private record RoomAccess(MemberDO member, String mode) {
        String channel() {
            return ROOM_MODE_PRIVATE.equals(mode) ? CHANNEL_WEB_PRIVATE : CHANNEL_WEB_GROUP;
        }
    }

    @Resource private LotteryConfigMapper lotteryConfigMapper;
    @Resource private SystemStateMapper systemStateMapper;
    @Resource private MarketConnectionMapper marketConnectionMapper;
    @Resource private LinkConfigMapper linkConfigMapper;
    @Resource private ChimaConfigMapper chimaConfigMapper;
    @Resource private SwitchSettingMapper switchSettingMapper;
    @Resource private IntegrationMapper integrationMapper;
    @Resource private OddMapper oddMapper;
    @Resource private MemberMapper memberMapper;
    @Resource private AmountRecordMapper amountRecordMapper;
    @Resource private OrderMapper orderMapper;
    @Resource private BetItemMapper betItemMapper;
    @Resource private DrawMapper drawMapper;
    @Resource private IssueMapper issueMapper;
    @Resource private IssueTransitionMapper issueTransitionMapper;
    @Resource private PresetOrderMapper presetOrderMapper;
    @Resource private QuickCommandMapper quickCommandMapper;
    @Resource private FollowOrderMapper followOrderMapper;
    @Resource private OperationLogMapper operationLogMapper;
    @Resource private MessageMapper messageMapper;
    @Resource private RebateRecordMapper rebateRecordMapper;
    @Resource private BalanceLedgerMapper balanceLedgerMapper;
    @Resource private ChimaRecordMapper chimaRecordMapper;
    @Resource private LotteryBettingService bettingService;
    @Resource private LotteryRoomMessagePolicy roomMessagePolicy;
    @Resource private LotteryRobotReplyTemplate robotReplyTemplate;
    @Resource private LotteryIssueFreshnessPolicy issueFreshnessPolicy;
    @Resource private LotteryRebateCalculator rebateCalculator;
    @Resource private LotteryChimaCalculator chimaCalculator;
    @Resource private LotteryOwnerInitializationService ownerInitializationService;
    @Resource private LotteryDrawVerificationService drawVerificationService;
    @Resource private LotteryBalanceLedgerService balanceLedgerService;
    @Resource private MarketCredentialService marketCredentialService;
    @Resource private LotteryMarketSyncService marketSyncService;
    @Resource private SecurityFrameworkService securityFrameworkService;
    @Resource private AdminUserService adminUserService;
    @Resource private ApplicationEventPublisher eventPublisher;

    @Override
    public Map<String, Object> getBootstrap() {
        Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
        if (loginUserId != null && !securityFrameworkService.hasRole("super_admin")) {
            AdminUserDO loginUser = adminUserService.getUser(loginUserId);
            if (loginUser != null) {
                ownerInitializationService.initializeAutomatically(TenantContextHolder.getRequiredTenantId(),
                        loginUserId, loginUser.getUsername());
            }
        }
        LotteryConfigDO config = findUserConfig(loginUserId);
        SystemStateDO state = first(systemStateMapper.selectList(null));
        MarketConnectionDO market = findUserMarket(loginUserId);
        IssueDO currentIssue = loginUserId == null ? null : DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, loginUserId)
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        List<IssueDO> drawAlerts = loginUserId == null ? List.of() : DataPermissionUtils.executeIgnore(() ->
                issueMapper.selectList(new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, loginUserId)
                        .and(wrapper -> wrapper.in(IssueDO::getStatus, "DRAW_ABNORMAL", "DRAW_PENDING")
                                .or().ne(IssueDO::getError, ""))
                        .orderByDesc(IssueDO::getPeriod).last("LIMIT 20")));
        LinkConfigDO links = loginUserId == null ? null : DataPermissionUtils.executeIgnore(() ->
                linkConfigMapper.selectOne(new LambdaQueryWrapper<LinkConfigDO>()
                        .eq(LinkConfigDO::getUserId, loginUserId).last("LIMIT 1")));
        ChimaConfigDO chimaConfig = first(chimaConfigMapper.selectList(null));
        List<SwitchSettingDO> switches = switchSettingMapper.selectList(new LambdaQueryWrapper<SwitchSettingDO>()
                .orderByAsc(SwitchSettingDO::getSettingKey));
        List<IntegrationDO> integrations = integrationMapper.selectList(new LambdaQueryWrapper<IntegrationDO>()
                .orderByAsc(IntegrationDO::getIntegrationKey));
        List<OddDO> odds = getEffectiveOdds(loginUserId);
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>().orderByAsc(MemberDO::getId));
        List<AmountRecordDO> amountRecords = amountRecordMapper.selectList(new LambdaQueryWrapper<AmountRecordDO>()
                .ne(AmountRecordDO::getRecordSource, TYPE_AUTO_PROXY)
                .orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 500"));
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 1000"));
        Set<String> orderIds = orders.stream().map(OrderDO::getId).collect(Collectors.toSet());
        List<BetItemDO> items = orderIds.isEmpty() ? List.of() : betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, orderIds));
        Map<String, List<BetItemDO>> itemsByOrder = items.stream().collect(Collectors.groupingBy(BetItemDO::getOrderId));
        Map<String, String> drawResultsByPeriod = drawResultsForOrders(loginUserId, orders);
        List<DrawDO> draws = drawMapper.selectList(new LambdaQueryWrapper<DrawDO>()
                .orderByDesc(DrawDO::getPeriod).last("LIMIT 500"));
        List<PresetOrderDO> presets = presetOrderMapper.selectList(new LambdaQueryWrapper<PresetOrderDO>()
                .orderByAsc(PresetOrderDO::getId));
        List<QuickCommandDO> commands = getEffectiveQuickCommands(loginUserId, false);
        List<FollowOrderDO> follows = followOrderMapper.selectList(new LambdaQueryWrapper<FollowOrderDO>()
                .orderByDesc(FollowOrderDO::getCreateTime));
        List<OperationLogDO> operators = operationLogMapper.selectList(new LambdaQueryWrapper<OperationLogDO>()
                .orderByDesc(OperationLogDO::getCreateTime).last("LIMIT 1000"));
        List<RebateRecordDO> rebates = rebateRecordMapper.selectList(null);

        Map<String, Object> result = new LinkedHashMap<>();
        List<MemberDO> realMembers = members.stream().filter(item -> !isAutoProxy(item)).toList();
        LocalDateTime onlineCutoff = LocalDateTime.now().minus(MEMBER_ONLINE_TIMEOUT);
        long onlineMembers = realMembers.stream().filter(item -> isMemberOnline(item, onlineCutoff)).count();
        boolean dashboard = has("lottery:dashboard:query");
        result.put("operator", dashboard ? map("username", state == null ? loginName() : state.getOperatorUsername(),
                "expireAt", state == null ? "" : date(state.getExpireAt())) : map("username", "", "expireAt", ""));
        result.put("room", dashboard ? map("open", state != null && bool(state.getRoomOpen()),
                "online", onlineMembers) : map("open", false, "online", 0));
        result.put("dashboardStats", dashboard ? map("totalMembers", realMembers.size(),
                "onlineMembers", onlineMembers,
                "pendingDeposits", amountRecords.stream().filter(item -> "上分".equals(item.getType())
                        && "待审核".equals(item.getStatus())).count())
                : map("totalMembers", 0, "onlineMembers", 0, "pendingDeposits", 0));
        result.put("switches", dashboard ? switches.stream().collect(Collectors.toMap(SwitchSettingDO::getSettingKey,
                item -> bool(item.getEnabled()), (a, b) -> b, LinkedHashMap::new)) : Map.of());
        result.put("switchLabels", dashboard ? switches.stream().collect(Collectors.toMap(SwitchSettingDO::getSettingKey,
                SwitchSettingDO::getLabel, (a, b) -> b, LinkedHashMap::new)) : Map.of());
        result.put("integrations", dashboard ? integrations.stream().collect(Collectors.toMap(IntegrationDO::getIntegrationKey,
                item -> map("name", item.getName(), "account", value(item.getAccount(), ""),
                        "group", value(item.getGroupName(), ""), "status", value(item.getStatus(), "未登录")),
                (a, b) -> b, LinkedHashMap::new)) : Map.of());

        result.put("config", has("lottery:config:manage") ? configMap(config) : Map.of());
        result.put("market", has("lottery:config:manage") ? marketMap(market) : null);
        result.put("issue", has("lottery:draw:manage") && currentIssue != null ? issueMap(currentIssue) : null);
        result.put("drawAlerts", has("lottery:draw:manage")
                ? drawAlerts.stream().map(this::issueMap).toList() : List.of());
        result.put("links", has("lottery:link:manage") ? map(
                "groupLinkEnabled", links == null || links.getGroupLinkEnabled() == null || bool(links.getGroupLinkEnabled()),
                "privateLinkEnabled", links == null || links.getPrivateLinkEnabled() == null || bool(links.getPrivateLinkEnabled()),
                "defaultRoomMode", links == null ? ROOM_MODE_GROUP
                        : value(links.getDefaultRoomMode(), ROOM_MODE_GROUP),
                "bound", links != null) : Map.of());
        result.put("odds", has("lottery:odds:manage") ? odds.stream().map(this::oddMap).toList() : List.of());

        boolean separateDragonRebate = loginUserId != null && enabled("dragonTigerSeparateRebate", loginUserId);
        result.put("members", hasAny("lottery:member:manage", "lottery:rebate:manage")
                ? members.stream().map(item -> memberMap(item, rebateCalculator.calculate(item, orders, itemsByOrder,
                        rebates, separateDragonRebate))).toList() : List.of());
        result.put("amountRecords", has("lottery:amount:manage") ? amountRecords.stream().map(this::amountRecordMap).toList() : List.of());
        result.put("orders", hasAny("lottery:order:manage", "lottery:history:query")
                ? orders.stream().map(item -> orderMap(item, itemsByOrder.getOrDefault(item.getId(), List.of()),
                        drawResultsByPeriod.get(item.getPeriod()))).toList() : List.of());
        result.put("drawHistory", has("lottery:draw:manage") ? draws.stream().map(this::drawMap).toList() : List.of());
        result.put("fakeOrders", has("lottery:preset:manage") ? presets.stream().map(item -> presetMap(item, odds)).toList() : List.of());
        result.put("quickCommands", has("lottery:quick-command:manage") ? commands.stream().map(this::quickCommandMap).toList() : List.of());
        result.put("followOrders", has("lottery:follow:manage") ? follows.stream().map(this::followMap).toList() : List.of());
        result.put("operators", has("lottery:operator:query") ? operators.stream().map(this::operationMap).toList() : List.of());
        result.put("messages", List.of());
        result.put("chimaConfig", has("lottery:chima-config:manage") ? chimaConfigMap(chimaConfig) : Map.of());
        result.put("chimaRecords", has("lottery:chima-record:manage")
                ? calculatePeriodChima(orders, members, state) : List.of());
        return result;
    }

    private Map<String, Object> configMap(LotteryConfigDO item) {
        return map("url", item == null ? "" : value(item.getUpstreamUrl(), ""),
                "account", item == null ? "" : value(item.getUpstreamAccount(), ""), "password", "",
                "hasPassword", item != null && StrUtil.isNotBlank(item.getMarketPasswordEncrypted()),
                "alertValue", item == null ? ZERO : money(item.getAlertValue()),
                "bossMode", item != null && bool(item.getBossMode()), "playType", item == null ? 2 : value(item.getPlayType(), 2),
                "useProxy", item == null || bool(item.getUseProxy()), "bound", item != null);
    }

    private Map<String, Object> marketMap(MarketConnectionDO item) {
        return map("status", item == null ? "未配置" : value(item.getStatus(), "未配置"),
                "lineUrl", item == null ? "" : value(item.getLineUrl(), ""),
                "displayAccount", item == null ? "" : value(item.getDisplayAccount(), ""),
                "balance", item == null ? null : money(item.getBalance()), "error", item == null ? "" : value(item.getError(), ""),
                "lastLoginAt", item == null ? "" : date(item.getLastLoginAt()), "lastSyncAt", item == null ? "" : date(item.getLastSyncAt()));
    }

    private Map<String, Object> oddMap(OddDO item) {
        return map("id", item.getCode(), "play", item.getPlay(), "item", item.getItem(), "rate", money(item.getRate()),
                "secondaryRate", money(item.getSecondaryRate()), "minLimit", money(item.getMinLimit()),
                "maxLimit", money(item.getMaxLimit()), "status", item.getStatus());
    }

    private Map<String, Object> memberMap(MemberDO item, LotteryRebateCalculator.RebateResult rebate) {
        String displayStatus = isAutoProxy(item) ? value(item.getStatus(), "离线")
                : isMemberOnline(item, LocalDateTime.now().minus(MEMBER_ONLINE_TIMEOUT)) ? "在线" : "离线";
        Map<String, Object> result = map("id", item.getId(), "name", item.getName(), "balance", money(item.getBalance()),
                "status", displayStatus, "lastSeenAt", date(item.getLastSeenAt()),
                "partner", item.getPartner(), "normalRate", money(item.getNormalRate()),
                "lhhRate", money(item.getLhhRate()), "partnerNormalRate", money(item.getPartnerNormalRate()),
                "partnerLhhRate", money(item.getPartnerLhhRate()), "tag", item.getTag(),
                "isPuller", "拉手".equals(item.getTag()), "externalNickname", item.getExternalNickname(),
                "totalBet", money(item.getTotalBet()), "profitLoss", money(item.getProfitLoss()),
                "memberType", memberType(item), "autoProxy", isAutoProxy(item),
                "autoBetEnabled", isAutoProxy(item) && bool(item.getAutoBetEnabled()),
                "autoTopUpAmount", money(value(item.getAutoTopUpAmount(), new BigDecimal("1000"))),
                "eatEnabled", bool(item.getEatEnabled()),
                "searchable", bool(item.getSearchable()), "fingerprint", value(item.getFingerprint(), ""),
                "privateChat", bool(item.getPrivateChat()), "webOnly", bool(item.getWebOnly()),
                "blueWhalePassword", value(item.getBlueWhalePassword(), ""), "avatar", value(item.getAvatar(), 1),
                "normalBet", rebate.normalBet(), "dragonBet", rebate.dragonBet(),
                "normalRebate", rebate.normalAmount(), "dragonRebate", rebate.dragonAmount(),
                "partnerRebate", rebate.partnerTotalAmount(), "totalRebate", rebate.combinedAmount());
        return result;
    }

    @Override
    public List<Map<String, Object>> getMemberSnapshots() {
        LocalDateTime onlineCutoff = LocalDateTime.now().minus(MEMBER_ONLINE_TIMEOUT);
        return memberMapper.selectList(new LambdaQueryWrapper<MemberDO>().orderByAsc(MemberDO::getId)).stream()
                .map(item -> map("id", item.getId(), "balance", money(item.getBalance()),
                        "totalBet", money(item.getTotalBet()), "profitLoss", money(item.getProfitLoss()),
                        "status", isAutoProxy(item) ? value(item.getStatus(), "离线")
                                : isMemberOnline(item, onlineCutoff) ? "在线" : "离线",
                        "lastSeenAt", date(item.getLastSeenAt()), "version", value(item.getVersion(), 0)))
                .toList();
    }

    @Override
    public Map<String, Object> initializeOwner(Long userId) {
        AdminUserDO user = adminUserService.getUser(userId);
        if (user == null) {
            throw exception(RECORD_NOT_FOUND);
        }
        LotteryOwnerInitializationService.InitializationResult result = ownerInitializationService.initializeManually(
                TenantContextHolder.getRequiredTenantId(), userId, user.getUsername(),
                SecurityFrameworkUtils.getLoginUserId());
        return map("userId", result.userId(), "username", user.getUsername(),
                "initializationCount", result.initializationCount(), "source", result.source(),
                "schemaVersion", result.schemaVersion());
    }

    private Map<String, Object> amountRecordMap(AmountRecordDO item) {
        return map("id", item.getId(), "member", item.getMemberName(), "type", item.getType(), "amount", money(item.getAmount()),
                "status", item.getStatus(), "recordSource", value(item.getRecordSource(), TYPE_PLAYER),
                "createdAt", date(item.getCreateTime()), "remark", value(item.getRemark(), ""),
                "auditedAt", item.getAuditedAt() == null ? null : date(item.getAuditedAt()));
    }

    private Map<String, Object> balanceLedgerMap(BalanceLedgerDO item) {
        return map("id", item.getId(), "businessType", item.getBusinessType(),
                "type", balanceBusinessLabel(item.getBusinessType()), "businessId", item.getBusinessId(),
                "direction", item.getDirection(), "amount", money(item.getAmount()),
                "balanceBefore", money(item.getBalanceBefore()), "balanceAfter", money(item.getBalanceAfter()),
                "actor", value(item.getActor(), ""), "remark", value(item.getRemark(), ""),
                "createdAt", date(item.getCreateTime()));
    }

    private Map<String, Object> orderMap(OrderDO item, List<BetItemDO> bets) {
        return orderMap(item, bets, null);
    }

    private Map<String, Object> orderMap(OrderDO item, List<BetItemDO> bets, String drawResult) {
        return map("id", item.getId(), "member", item.getMemberName(), "period", item.getPeriod(), "content", item.getContent(),
                "amount", money(item.getAmount()), "win", money(item.getWin()), "status", item.getStatus(), "source", item.getSource(),
                "orderType", value(item.getOrderType(), TYPE_PLAYER), "autoProxy", isAutoProxyOrder(item),
                "deliveryMode", item.getDeliveryMode(), "marketStatus", item.getMarketStatus(), "marketOrderId", item.getMarketOrderId(),
                "marketError", item.getMarketError(), "marketAttempts", value(item.getMarketAttempts(), 0),
                "createdAt", date(item.getCreateTime()), "settledAt", date(item.getSettledAt()),
                "drawResult", value(drawResult, ""), "itemCount", bets.size(),
                "items", bets.stream().map(this::betItemMap).toList());
    }

    private Map<String, String> drawResultsForOrders(Long userId, List<OrderDO> orders) {
        Set<String> periods = orders.stream().map(OrderDO::getPeriod).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        if (userId == null || periods.isEmpty()) {
            return Map.of();
        }
        return drawMapper.selectList(new LambdaQueryWrapper<DrawDO>()
                        .eq(DrawDO::getUserId, userId).in(DrawDO::getPeriod, periods))
                .stream().filter(item -> drawVerificationService.isTrusted(item.getResult()))
                .collect(Collectors.toMap(DrawDO::getPeriod, item -> value(item.getResult(), ""),
                        (first, ignored) -> first));
    }

    private Map<String, Object> betItemMap(BetItemDO item) {
        return map("id", item.getId(), "play", item.getPlay(), "selection", item.getSelection(), "amount", money(item.getAmount()),
                "odds", money(item.getOdds()), "won", item.getWon(), "payout", money(item.getPayout()));
    }

    private Map<String, Object> drawMap(DrawDO item) {
        boolean trusted = drawVerificationService.isTrusted(item.getResult());
        return map("period", item.getPeriod(), "result", item.getResult(), "bigSmall", item.getBigSmall(),
                "oddEven", item.getOddEven(), "dragonTiger", item.getDragonTiger(),
                "status", trusted ? item.getStatus() : "异常", "valid", trusted,
                "settledAt", trusted ? date(item.getSettledAt()) : "");
    }

    private Map<String, Object> presetMap(PresetOrderDO item, List<OddDO> odds) {
        int count = 0;
        BigDecimal total = ZERO;
        String error = "";
        try {
            List<LotteryBettingService.ParsedBet> parsed = bettingService.parse(item.getContent(), odds);
            count = parsed.size();
            total = parsed.stream().map(LotteryBettingService.ParsedBet::amount).reduce(ZERO, BigDecimal::add);
        } catch (RuntimeException ex) {
            error = "格式无法识别";
        }
        return map("id", item.getId(), "member", item.getMember(), "content", item.getContent(),
                "createdAt", date(item.getCreateTime()), "parsedCount", count, "parsedAmount", money(total), "validationError", error);
    }

    private Map<String, Object> quickCommandMap(QuickCommandDO item) {
        return map("id", item.getId(), "label", item.getLabel(), "content", item.getContent(), "sort", item.getSort(),
                "enabled", bool(item.getEnabled()), "createdAt", date(item.getCreateTime()));
    }

    private Map<String, Object> followMap(FollowOrderDO item) {
        return map("id", item.getId(), "source", item.getSource(), "target", item.getTarget(), "ratio", money(item.getRatio()),
                "enabled", bool(item.getEnabled()), "createdAt", date(item.getCreateTime()));
    }

    private Map<String, Object> operationMap(OperationLogDO item) {
        return map("id", item.getId(), "operator", item.getOperator(), "member", item.getMember(),
                "action", item.getAction(), "time", date(item.getCreateTime()));
    }

    private Map<String, Object> messageMap(MessageDO item) {
        return map("id", item.getId(), "channel", item.getChannel(), "memberId", item.getMemberId(),
                "member", item.getMember(), "period", item.getPeriod(),
                "content", item.getContent(), "status", item.getStatus(), "orderId", item.getOrderId(), "error", item.getError(),
                "commandType", item.getCommandType(), "messageType", value(item.getMessageType(), TYPE_PLAYER),
                "reply", normalizeCheckMarks(item.getReply()), "processedAt", date(item.getProcessedAt()),
                "createdAt", date(item.getCreateTime()), "time", date(item.getCreateTime()));
    }

    private List<Map<String, Object>> messageDisplayRows(MessageDO item) {
        String sourceMember = StrUtil.blankToDefault(item.getMember(), "未知会员");
        List<Map<String, Object>> rows = new ArrayList<>(2);
        if (StrUtil.isNotBlank(item.getReply())) {
            rows.add(map("id", "robot-" + item.getId(), "sender", "机器人", "sourceMember", sourceMember,
                    "period", value(item.getPeriod(), ""), "content", normalizeCheckMarks(item.getReply()), "kind", "robot",
                    "time", date(value(item.getProcessedAt(), item.getCreateTime()))));
        }
        if (StrUtil.isNotBlank(item.getContent())) {
            rows.add(map("id", "member-" + item.getId(), "sender", sourceMember, "sourceMember", sourceMember,
                    "period", value(item.getPeriod(), ""), "content", item.getContent(), "kind", "member",
                    "time", date(item.getCreateTime())));
        }
        return rows;
    }

    private String normalizeCheckMarks(String text) {
        return text == null ? null : text.replace('√', '✓');
    }

    private Map<String, Object> chimaConfigMap(ChimaConfigDO item) {
        return map("siZiXian", item == null ? ZERO : money(item.getSiZiXian()),
                "sanZiXian", item == null ? ZERO : money(item.getSanZiXian()), "erZiXian", item == null ? ZERO : money(item.getErZiXian()),
                "danZiXian", item == null ? ZERO : money(item.getDanZiXian()), "siDingWei", item == null ? ZERO : money(item.getSiDingWei()),
                "sanDingWei", item == null ? ZERO : money(item.getSanDingWei()), "erDingWei", item == null ? ZERO : money(item.getErDingWei()),
                "yiDingWei", item == null ? ZERO : money(item.getYiDingWei()), "yinKuiMax", item == null ? ZERO : money(item.getYinKuiMax()),
                "yinKuiMin", item == null ? ZERO : money(item.getYinKuiMin()), "bound", item != null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setSwitch(String key, Boolean value) {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        SwitchSettingDO setting = DataPermissionUtils.executeIgnore(() -> switchSettingMapper.selectOne(
                new LambdaQueryWrapper<SwitchSettingDO>().eq(SwitchSettingDO::getUserId, userId)
                        .eq(SwitchSettingDO::getSettingKey, key)));
        if (setting == null) throw exception(SWITCH_NOT_FOUND);
        setting.setEnabled(value);
        switchSettingMapper.updateById(setting);
        if (Boolean.TRUE.equals(value) && ("wangkaEnable".equals(key) || "syncEnable".equals(key))) {
            String otherKey = "wangkaEnable".equals(key) ? "syncEnable" : "wangkaEnable";
            SwitchSettingDO other = DataPermissionUtils.executeIgnore(() -> switchSettingMapper.selectOne(
                    new LambdaQueryWrapper<SwitchSettingDO>().eq(SwitchSettingDO::getUserId, userId)
                            .eq(SwitchSettingDO::getSettingKey, otherKey)));
            if (other != null) {
                other.setEnabled(false);
                switchSettingMapper.updateById(other);
            }
        }
        log("-", "修改开关 " + key + "=" + value);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRoom(Boolean open) {
        if (Boolean.TRUE.equals(open)) {
            LotteryConfigDO config = requireConfig();
            if (!Boolean.TRUE.equals(config.getBossMode())
                    && (StrUtil.isBlank(config.getUpstreamUrl())
                    || StrUtil.isBlank(config.getUpstreamAccount())
                    || StrUtil.isBlank(config.getMarketPasswordEncrypted()))) {
                throw exception(MARKET_CONFIG_REQUIRED);
            }
        }
        SystemStateDO state = requireState();
        state.setRoomOpen(open);
        systemStateMapper.updateById(state);
        log("-", Boolean.TRUE.equals(open) ? "开启房间" : "关闭房间");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(LotteryReqVO.Config reqVO) {
        LotteryConfigDO config = requireConfig();
        String url = value(reqVO.getUrl(), "").trim();
        String account = value(reqVO.getAccount(), "").trim();
        boolean hasNewPassword = StrUtil.isNotBlank(reqVO.getPassword()) && !"********".equals(reqVO.getPassword());
        String encrypted = hasNewPassword ? marketCredentialService.encrypt(reqVO.getPassword().trim())
                : value(config.getMarketPasswordEncrypted(), "");
        config.setUpstreamUrl(url);
        config.setUpstreamAccount(account);
        config.setMarketPasswordEncrypted(encrypted);
        config.setAlertValue(value(reqVO.getAlertValue(), ZERO));
        config.setBossMode(reqVO.getBossMode());
        config.setPlayType(reqVO.getPlayType());
        config.setUseProxy(reqVO.getUseProxy());
        lotteryConfigMapper.updateById(config);
        log("-", "保存配置管理");
    }

    @Override
    public Map<String, Object> testConfig(LotteryReqVO.Config reqVO) {
        return marketSyncService.test(reqVO, findUserConfig(SecurityFrameworkUtils.getLoginUserId()));
    }

    @Override
    public Map<String, Object> syncMarket() {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        return marketSyncService.syncCurrent(TenantContextHolder.getRequiredTenantId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLinks(LotteryReqVO.LinkConfig reqVO) {
        boolean groupEnabled = bool(reqVO.getGroupLinkEnabled());
        boolean privateEnabled = bool(reqVO.getPrivateLinkEnabled());
        String defaultMode = reqVO.getDefaultRoomMode();
        if (!groupEnabled && !privateEnabled
                || ROOM_MODE_GROUP.equals(defaultMode) && !groupEnabled
                || ROOM_MODE_PRIVATE.equals(defaultMode) && !privateEnabled) {
            throw exception(ROOM_MODE_REQUIRED);
        }
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        LinkConfigDO links = DataPermissionUtils.executeIgnore(() -> linkConfigMapper.selectOne(
                new LambdaQueryWrapper<LinkConfigDO>().eq(LinkConfigDO::getUserId, userId).last("LIMIT 1")));
        if (links == null) {
            links = new LinkConfigDO();
            links.setUserId(userId);
            links.setDeviceId("");
            links.setDealerUrl("");
            links.setRoomUrl("");
            links.setShortUrl("");
            links.setQrMode("");
            links.setShortUrlMode(2);
            links.setGroupLinkEnabled(groupEnabled);
            links.setPrivateLinkEnabled(privateEnabled);
            links.setDefaultRoomMode(defaultMode);
            linkConfigMapper.insert(links);
        } else {
            links.setGroupLinkEnabled(groupEnabled);
            links.setPrivateLinkEnabled(privateEnabled);
            links.setDefaultRoomMode(defaultMode);
            linkConfigMapper.updateById(links);
        }
        log("-", "保存链接配置");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveChimaConfig(LotteryReqVO.ChimaConfig reqVO) {
        ChimaConfigDO item = first(chimaConfigMapper.selectList(null));
        boolean create = item == null;
        if (create) item = new ChimaConfigDO();
        item.setSiZiXian(value(reqVO.getSiZiXian(), ZERO));
        item.setSanZiXian(value(reqVO.getSanZiXian(), ZERO));
        item.setErZiXian(value(reqVO.getErZiXian(), ZERO));
        item.setDanZiXian(value(reqVO.getDanZiXian(), ZERO));
        item.setSiDingWei(value(reqVO.getSiDingWei(), ZERO));
        item.setSanDingWei(value(reqVO.getSanDingWei(), ZERO));
        item.setErDingWei(value(reqVO.getErDingWei(), ZERO));
        item.setYiDingWei(value(reqVO.getYiDingWei(), ZERO));
        item.setYinKuiMax(value(reqVO.getYinKuiMax(), ZERO));
        item.setYinKuiMin(value(reqVO.getYinKuiMin(), ZERO));
        if (create) chimaConfigMapper.insert(item); else chimaConfigMapper.updateById(item);
        log("-", "保存吃码额度配置");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindIntegration(String key, LotteryReqVO.Integration reqVO) {
        requireIntegrationKey(key);
        IntegrationDO item = integrationMapper.selectOne(new LambdaQueryWrapper<IntegrationDO>()
                .eq(IntegrationDO::getIntegrationKey, key));
        if (item == null) {
            item = new IntegrationDO();
            item.setIntegrationKey(key);
            item.setName(key);
            item.setStatus("待验证");
            item.setAccount(value(reqVO.getAccount(), ""));
            item.setGroupName(value(reqVO.getGroup(), ""));
            integrationMapper.insert(item);
        } else {
            item.setAccount(value(reqVO.getAccount(), ""));
            item.setGroupName(value(reqVO.getGroup(), ""));
            item.setStatus("待验证");
            integrationMapper.updateById(item);
        }
        log("-", "绑定第三方接入 " + key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindIntegration(String key) {
        requireIntegrationKey(key);
        IntegrationDO item = integrationMapper.selectOne(new LambdaQueryWrapper<IntegrationDO>()
                .eq(IntegrationDO::getIntegrationKey, key));
        if (item != null) {
            item.setAccount("");
            item.setGroupName("");
            item.setStatus("未登录");
            integrationMapper.updateById(item);
        }
        log("-", "解绑第三方接入 " + key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String saveMember(LotteryReqVO.Member reqVO) {
        MemberDO duplicate = memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>()
                .eq(MemberDO::getName, reqVO.getName()).ne(StrUtil.isNotBlank(reqVO.getId()), MemberDO::getId, reqVO.getId()));
        if (duplicate != null) throw exception(MEMBER_NAME_EXISTS);
        MemberDO item = StrUtil.isBlank(reqVO.getId()) ? null : memberMapper.selectById(reqVO.getId());
        boolean create = item == null;
        boolean wasAutoProxy = !create && isAutoProxy(item);
        BigDecimal oldBalance = create ? ZERO : money(item.getBalance());
        BigDecimal requestedBalance = money(value(reqVO.getBalance(), oldBalance));
        if (create) {
            item = new MemberDO();
            item.setId(id());
            item.setOpenId(IdUtil.fastSimpleUUID());
            item.setAvatar(1);
            item.setVersion(0);
        }
        item.setName(reqVO.getName());
        item.setBalance(oldBalance);
        item.setStatus(value(reqVO.getStatus(), "离线"));
        item.setPartner(value(reqVO.getPartner(), "无"));
        item.setNormalRate(value(reqVO.getNormalRate(), ZERO));
        item.setLhhRate(value(reqVO.getLhhRate(), ZERO));
        item.setPartnerNormalRate(value(reqVO.getPartnerNormalRate(), value(item.getPartnerNormalRate(), ZERO)));
        item.setPartnerLhhRate(value(reqVO.getPartnerLhhRate(), value(item.getPartnerLhhRate(), ZERO)));
        item.setTag(value(reqVO.getTag(), "普通"));
        item.setExternalNickname(value(reqVO.getExternalNickname(), ""));
        item.setTotalBet(value(reqVO.getTotalBet(), value(item.getTotalBet(), ZERO)));
        item.setProfitLoss(value(reqVO.getProfitLoss(), value(item.getProfitLoss(), ZERO)));
        String requestedType = reqVO.getAutoProxy() != null
                ? (Boolean.TRUE.equals(reqVO.getAutoProxy()) ? MEMBER_BOT : MEMBER_REAL)
                : StrUtil.isNotBlank(reqVO.getMemberType()) ? reqVO.getMemberType()
                : value(item.getMemberType(), MEMBER_REAL);
        requestedType = requestedType.trim().toUpperCase();
        if (!Set.of(MEMBER_REAL, MEMBER_BOT).contains(requestedType)) {
            requestedType = MEMBER_REAL;
        }
        boolean autoProxy = MEMBER_BOT.equals(requestedType);
        item.setMemberType(requestedType);
        item.setAutoProxy(autoProxy);
        item.setAutoBetEnabled(autoProxy && value(reqVO.getAutoBetEnabled(),
                reqVO.getAutoProxy() != null ? true
                        : create || !wasAutoProxy || item.getAutoBetEnabled() == null ? true : item.getAutoBetEnabled()));
        item.setAutoTopUpAmount(money(value(reqVO.getAutoTopUpAmount(),
                value(item.getAutoTopUpAmount(), new BigDecimal("1000")))));
        item.setEatEnabled(value(reqVO.getEatEnabled(), value(item.getEatEnabled(), false)));
        item.setSearchable(value(reqVO.getSearchable(), true));
        item.setFingerprint(value(reqVO.getFingerprint(), value(item.getFingerprint(), "")));
        item.setPrivateChat(value(reqVO.getPrivateChat(), false));
        item.setWebOnly(value(reqVO.getWebOnly(), false));
        item.setBlueWhalePassword(value(reqVO.getBlueWhalePassword(), ""));
        normalizeSelfPartner(item);
        if (create) {
            memberMapper.insert(item);
            if (requestedBalance.signum() > 0) {
                balanceLedgerService.change(item, requestedBalance, LotteryBalanceLedgerService.OPENING_BALANCE,
                        item.getId(), loginName(), "新建会员初始积分");
            }
        } else {
            int version = value(item.getVersion(), 0);
            item.setVersion(version + 1);
            int updated = memberMapper.update(item, new LambdaUpdateWrapper<MemberDO>().eq(MemberDO::getId, item.getId())
                    .eq(MemberDO::getUserId, item.getUserId()).eq(MemberDO::getVersion, version));
            if (updated != 1) throw exception(BET_STATE_CHANGED);
            BigDecimal delta = requestedBalance.subtract(oldBalance);
            if (delta.signum() != 0) {
                balanceLedgerService.change(item, delta, LotteryBalanceLedgerService.MANUAL_ADJUSTMENT,
                        "member-adjustment:" + id(), loginName(), "编辑会员资料调整积分");
            }
        }
        log(item.getName(), create ? "新增会员" : "修改会员");
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferMember(String id, LotteryReqVO.Transfer reqVO) {
        MemberDO member = requireMember(id);
        requireTransferType(reqVO.getType());
        String recordId = id();
        BigDecimal delta;
        if ("下分".equals(reqVO.getType())) {
            delta = reqVO.getAmount().negate();
        } else {
            delta = reqVO.getAmount();
        }
        balanceLedgerService.change(member, delta, "上分".equals(reqVO.getType())
                        ? LotteryBalanceLedgerService.DEPOSIT : LotteryBalanceLedgerService.WITHDRAW,
                recordId, loginName(), value(reqVO.getRemark(), "后台即时操作"));
        AmountRecordDO record = new AmountRecordDO();
        record.setId(recordId);
        record.setMemberId(member.getId());
        record.setMemberName(member.getName());
        record.setType(reqVO.getType());
        record.setAmount(reqVO.getAmount());
        record.setStatus("已通过");
        record.setRecordSource(isAutoProxy(member) ? TYPE_AUTO_PROXY : "ADMIN");
        record.setRemark(value(reqVO.getRemark(), "后台即时操作"));
        record.setAuditedAt(LocalDateTime.now());
        record.setAuditedBy(loginName());
        record.setUserId(member.getUserId());
        amountRecordMapper.insert(record);
        log(member.getName(), reqVO.getType() + " " + reqVO.getAmount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createAmountRequest(String id, LotteryReqVO.Transfer reqVO) {
        MemberDO member = requireMember(id);
        requireTransferType(reqVO.getType());
        if (isAutoProxy(member)) {
            Map<String, Object> result = approveAutoProxyTransfer(member, reqVO, "", "后台",
                    null, "自动托:" + member.getName());
            return String.valueOf(result.get("recordId"));
        }
        AmountRecordDO record = new AmountRecordDO();
        record.setId(id());
        record.setMemberId(member.getId());
        record.setMemberName(member.getName());
        record.setType(reqVO.getType());
        record.setAmount(reqVO.getAmount());
        record.setStatus("待审核");
        record.setRecordSource(isAutoProxy(member) ? TYPE_AUTO_PROXY : TYPE_PLAYER);
        record.setRemark(value(reqVO.getRemark(), ""));
        record.setUserId(member.getUserId());
        amountRecordMapper.insert(record);
        log(member.getUserId(), member.getName(), "提交" + reqVO.getType() + "申请 " + reqVO.getAmount());
        return record.getId();
    }

    @Override
    public List<Map<String, Object>> getAmountRecords() {
        return amountRecordMapper.selectList(new LambdaQueryWrapper<AmountRecordDO>()
                        .eq(AmountRecordDO::getUserId, SecurityFrameworkUtils.getLoginUserId())
                        .ne(AmountRecordDO::getRecordSource, TYPE_AUTO_PROXY)
                        .orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 500"))
                .stream().map(this::amountRecordMap).toList();
    }

    @Override
    public List<Map<String, Object>> getOrders() {
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, SecurityFrameworkUtils.getLoginUserId())
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 1000"));
        List<String> orderIds = orders.stream().map(OrderDO::getId).toList();
        Map<String, List<BetItemDO>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : betItemMapper.selectList(new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, orderIds))
                        .stream().collect(Collectors.groupingBy(BetItemDO::getOrderId));
        Map<String, String> drawResultsByPeriod = drawResultsForOrders(SecurityFrameworkUtils.getLoginUserId(), orders);
        return orders.stream().map(item -> orderMap(item, itemsByOrder.getOrDefault(item.getId(), List.of()),
                drawResultsByPeriod.get(item.getPeriod()))).toList();
    }

    @Override
    public Map<String, Object> getMemberDetails(String id) {
        MemberDO member = requireMember(id);
        boolean autoProxy = isAutoProxy(member);
        List<AmountRecordDO> amounts = autoProxy ? List.of() : amountRecordMapper.selectList(new LambdaQueryWrapper<AmountRecordDO>()
                .eq(AmountRecordDO::getMemberId, id).ne(AmountRecordDO::getRecordSource, TYPE_AUTO_PROXY)
                .orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 20"));
        List<OrderDO> orders = autoProxy ? List.of() : orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMemberId, id).ne(OrderDO::getOrderType, TYPE_AUTO_PROXY)
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 20"));
        List<String> ids = orders.stream().map(OrderDO::getId).toList();
        Map<String, List<BetItemDO>> items = ids.isEmpty() ? Map.of() : betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, ids)).stream()
                .collect(Collectors.groupingBy(BetItemDO::getOrderId));
        List<RebateRecordDO> rebates = rebateRecordMapper.selectList(new LambdaQueryWrapper<RebateRecordDO>()
                .eq(RebateRecordDO::getMemberId, id));
        List<BalanceLedgerDO> ledgers = autoProxy ? List.of() : balanceLedgerMapper.selectList(new LambdaQueryWrapper<BalanceLedgerDO>()
                .eq(BalanceLedgerDO::getMemberId, id).orderByDesc(BalanceLedgerDO::getCreateTime).last("LIMIT 100"));
        Map<String, Object> result = memberMap(member, rebateCalculator.calculate(member, orders, items, rebates,
                enabled("dragonTigerSeparateRebate", member.getUserId())));
        result.put("amountRecords", amounts.stream().map(this::amountRecordMap).toList());
        Map<String, String> drawResultsByPeriod = drawResultsForOrders(member.getUserId(), orders);
        result.put("orders", orders.stream().map(item -> orderMap(item, items.getOrDefault(item.getId(), List.of()),
                drawResultsByPeriod.get(item.getPeriod()))).toList());
        result.put("balanceLedgers", ledgers.stream().map(this::balanceLedgerMap).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> getMemberLinks(String id, String origin) {
        MemberDO member = requireMember(id);
        if (StrUtil.isBlank(member.getOpenId())) {
            member.setOpenId(IdUtil.fastSimpleUUID());
            memberMapper.updateById(member);
        }
        log(member.getName(), "生成会员链接");
        return linkPayload(member, origin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> rotateMemberLink(String id, String origin) {
        MemberDO member = requireMember(id);
        member.setOpenId(IdUtil.fastSimpleUUID());
        member.setVersion(value(member.getVersion(), 0) + 1);
        memberMapper.updateById(member);
        log(member.getName(), "更换会员链接");
        return linkPayload(member, origin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearMemberFingerprint(String id) {
        MemberDO member = requireMember(id);
        member.setFingerprint("");
        member.setVersion(value(member.getVersion(), 0) + 1);
        memberMapper.updateById(member);
        log(member.getName(), "抹除会员标识");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearAllFingerprints() {
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>().ne(MemberDO::getFingerprint, ""));
        members.forEach(item -> {
            item.setFingerprint("");
            item.setVersion(value(item.getVersion(), 0) + 1);
            memberMapper.updateById(item);
        });
        log("-", "抹除全部会员标识 " + members.size() + " 条");
        return members.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeMemberAvatar(String id) {
        MemberDO member = requireMember(id);
        int avatar = value(member.getAvatar(), 1) >= 9 ? 1 : value(member.getAvatar(), 1) + 1;
        member.setAvatar(avatar);
        member.setVersion(value(member.getVersion(), 0) + 1);
        memberMapper.updateById(member);
        log(member.getName(), "更换头像 " + avatar);
        return avatar;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearAllMemberFlows(String password) {
        verifyCurrentPassword(password);
        List<MemberDO> members = memberMapper.selectList(null);
        LocalDateTime now = LocalDateTime.now();
        members.forEach(item -> {
            item.setTotalBet(ZERO);
            item.setProfitLoss(ZERO);
            item.setFlowClearedAt(now);
            item.setVersion(value(item.getVersion(), 0) + 1);
            memberMapper.updateById(item);
        });
        chimaRecordMapper.selectList(null).forEach(item -> {
            item.setFakeAmount(ZERO);
            item.setTotalWin(ZERO);
            chimaRecordMapper.updateById(item);
        });
        log("-", "清理全部会员流水 " + members.size() + " 人");
        return members.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearMember(String id) {
        MemberDO member = requireMember(id);
        member.setTotalBet(ZERO);
        member.setProfitLoss(ZERO);
        member.setFlowClearedAt(LocalDateTime.now());
        member.setVersion(value(member.getVersion(), 0) + 1);
        memberMapper.updateById(member);
        log(member.getName(), "清理会员流水");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMember(String id) {
        MemberDO member = requireMember(id);
        memberMapper.deleteById(id);
        log(member.getName(), "删除会员");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDiscounts(LotteryReqVO.Discounts reqVO) {
        reqVO.getMembers().forEach(item -> {
            MemberDO member = requireMember(item.getId());
            if (isAutoProxy(member)) return;
            if (item.getNormalRate() != null) member.setNormalRate(item.getNormalRate());
            if (item.getLhhRate() != null) member.setLhhRate(item.getLhhRate());
            if (item.getPartnerNormalRate() != null) member.setPartnerNormalRate(item.getPartnerNormalRate());
            if (item.getPartnerLhhRate() != null) member.setPartnerLhhRate(item.getPartnerLhhRate());
            if (item.getPartner() != null) member.setPartner(StrUtil.blankToDefault(item.getPartner().trim(), "无"));
            if (item.getPuller() != null) member.setTag(Boolean.TRUE.equals(item.getPuller()) ? "拉手" : "普通");
            normalizeSelfPartner(member);
            memberMapper.updateById(member);
        });
        normalizePullerRelations();
        log("-", "保存会员返水及拉手设置");
    }

    private void normalizeSelfPartner(MemberDO member) {
        if (StrUtil.isBlank(member.getPartner()) || "无".equals(member.getPartner())
                || Objects.equals(member.getName(), member.getPartner())) {
            member.setPartner("无");
        }
    }

    private void normalizePullerRelations() {
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>()
                .ne(MemberDO::getMemberType, MEMBER_BOT).eq(MemberDO::getAutoProxy, false));
        Set<String> pullerNames = members.stream().filter(member -> "拉手".equals(member.getTag()))
                .map(MemberDO::getName).collect(Collectors.toSet());
        for (MemberDO member : members) {
            String partner = value(member.getPartner(), "无");
            if (!pullerNames.contains(partner) || Objects.equals(member.getName(), partner)) {
                if (!"无".equals(partner)) {
                    member.setPartner("无");
                    memberMapper.updateById(member);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRebates() {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        return applyRebatesInternal(userId, loginName());
    }

    private Map<String, Object> applyRebatesInternal(Long userId, String actor) {
        lockOwnerFinance(userId);
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId).ne(OrderDO::getOrderType, TYPE_AUTO_PROXY)
                .in(OrderDO::getStatus, "已中奖", "未中奖")));
        List<String> orderIds = orders.stream().map(OrderDO::getId).toList();
        List<BetItemDO> items = orderIds.isEmpty() ? List.of() : DataPermissionUtils.executeIgnore(() -> betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, userId).in(BetItemDO::getOrderId, orderIds)));
        Map<String, List<BetItemDO>> itemsByOrder = items.stream().collect(Collectors.groupingBy(BetItemDO::getOrderId));
        List<RebateRecordDO> rebateRecords = DataPermissionUtils.executeIgnore(() -> rebateRecordMapper.selectList(
                new LambdaQueryWrapper<RebateRecordDO>().eq(RebateRecordDO::getUserId, userId)));
        BigDecimal total = ZERO;
        int count = 0;
        List<Map<String, Object>> paidMembers = new ArrayList<>();
        List<MemberDO> members = DataPermissionUtils.executeIgnore(() -> memberMapper.selectList(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, userId)
                        .ne(MemberDO::getMemberType, MEMBER_BOT).eq(MemberDO::getAutoProxy, false)));
        Map<String, MemberDO> pullersByName = members.stream().filter(member -> "拉手".equals(member.getTag()))
                .collect(Collectors.toMap(MemberDO::getName, Function.identity(), (first, ignored) -> first));
        boolean separateDragonRebate = enabled("dragonTigerSeparateRebate", userId);
        for (MemberDO member : members) {
            LotteryRebateCalculator.RebateResult rebate = rebateCalculator.calculate(member, orders, itemsByOrder,
                    rebateRecords, separateDragonRebate);
            BigDecimal ownAmount = rebate.totalAmount();
            MemberDO puller = pullersByName.get(member.getPartner());
            BigDecimal pullerAmount = puller == null || Objects.equals(puller.getId(), member.getId())
                    ? ZERO : rebate.partnerTotalAmount();
            BigDecimal amount = ownAmount.add(pullerAmount);
            if (amount.signum() <= 0) continue;
            RebateRecordDO record = new RebateRecordDO();
            record.setId(id());
            record.setMemberId(member.getId());
            record.setNormalBet(rebate.pendingNormalBet());
            record.setDragonBet(rebate.pendingDragonBet());
            record.setNormalAmount(rebate.normalAmount());
            record.setDragonAmount(rebate.dragonAmount());
            record.setPartnerMemberId(pullerAmount.signum() > 0 ? puller.getId() : null);
            record.setPartnerNormalAmount(pullerAmount.signum() > 0 ? rebate.partnerNormalAmount() : ZERO);
            record.setPartnerDragonAmount(pullerAmount.signum() > 0 ? rebate.partnerDragonAmount() : ZERO);
            record.setPartnerTotalAmount(pullerAmount);
            record.setTotalAmount(amount);
            record.setUserId(member.getUserId());
            rebateRecordMapper.insert(record);
            if (ownAmount.signum() > 0) {
                String remark = "普通 " + rebate.normalAmount() + " / 龙虎 " + rebate.dragonAmount();
                balanceLedgerService.change(member, ownAmount, LotteryBalanceLedgerService.REBATE,
                        record.getId(), actor, remark);
                insertRebateAmountRecord(record.getId(), member, ownAmount, remark, actor);
                count++;
                paidMembers.add(map("id", member.getId(), "name", member.getName(), "amount", ownAmount,
                        "role", "玩家"));
            }
            if (pullerAmount.signum() > 0) {
                String remark = "拉手返水，来源 " + member.getName() + "：普通 " + rebate.partnerNormalAmount()
                        + " / 龙虎 " + rebate.partnerDragonAmount();
                String amountRecordId = id();
                balanceLedgerService.change(puller, pullerAmount, LotteryBalanceLedgerService.REBATE,
                        amountRecordId, actor, remark);
                insertRebateAmountRecord(amountRecordId, puller, pullerAmount, remark, actor);
                count++;
                paidMembers.add(map("id", puller.getId(), "name", puller.getName(), "amount", pullerAmount,
                        "role", "拉手", "sourceMember", member.getName()));
            }
            total = total.add(amount);
        }
        logAs(userId, actor, "-", "发放返水 " + count + " 人，合计 " + money(total));
        return map("count", count, "amount", money(total), "totalAmount", money(total), "members", paidMembers);
    }

    private void insertRebateAmountRecord(String id, MemberDO member, BigDecimal amount, String remark, String actor) {
        AmountRecordDO amountRecord = new AmountRecordDO();
        amountRecord.setId(id);
        amountRecord.setMemberId(member.getId());
        amountRecord.setMemberName(member.getName());
        amountRecord.setType("返水");
        amountRecord.setAmount(amount);
        amountRecord.setStatus("已通过");
        amountRecord.setRecordSource("REBATE");
        amountRecord.setRemark(remark);
        amountRecord.setAuditedAt(LocalDateTime.now());
        amountRecord.setAuditedBy(actor);
        amountRecord.setUserId(member.getUserId());
        amountRecordMapper.insert(amountRecord);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearChimaRecords(String password) {
        verifyCurrentPassword(password);
        SystemStateDO state = requireState();
        state.setChimaClearedAt(LocalDateTime.now());
        systemStateMapper.updateById(state);
        chimaRecordMapper.selectList(null).forEach(item -> {
            item.setFakeAmount(ZERO);
            item.setTotalWin(ZERO);
            chimaRecordMapper.updateById(item);
        });
        log("-", "清理吃码记录");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditAmount(String id, LotteryReqVO.Audit reqVO) {
        AmountRecordDO record = amountRecordMapper.selectById(id);
        if (record == null) throw exception(RECORD_NOT_FOUND);
        if (!"待审核".equals(record.getStatus())) throw exception(RECORD_ALREADY_PROCESSED);
        if (!Set.of("已通过", "已拒绝").contains(reqVO.getStatus())) throw exception(RECORD_NOT_FOUND);
        requireTransferType(record.getType());
        MemberDO member = requireMember(record.getMemberId());
        record.setStatus(reqVO.getStatus());
        record.setRemark(StrUtil.blankToDefault(reqVO.getRemark(), value(record.getRemark(), "")));
        record.setAuditedAt(LocalDateTime.now());
        record.setAuditedBy(loginName());
        int audited = amountRecordMapper.update(record, new LambdaUpdateWrapper<AmountRecordDO>()
                .eq(AmountRecordDO::getId, id).eq(AmountRecordDO::getUserId, record.getUserId())
                .eq(AmountRecordDO::getStatus, "待审核"));
        if (audited != 1) throw exception(RECORD_ALREADY_PROCESSED);
        if ("已通过".equals(reqVO.getStatus())) {
            BigDecimal delta = "下分".equals(record.getType()) ? record.getAmount().negate() : record.getAmount();
            balanceLedgerService.change(member, delta, "上分".equals(record.getType())
                            ? LotteryBalanceLedgerService.DEPOSIT : LotteryBalanceLedgerService.WITHDRAW,
                    record.getId(), loginName(), value(record.getRemark(), "上下分审核"));
        }
        updateAmountRequestReply(record, member);
        log(member.getName(), record.getType() + reqVO.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> placeBet(LotteryReqVO.PlaceBet reqVO) {
        return betResultMap(placeBetInternal(reqVO, loginName(), TYPE_PLAYER));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Map<String, Object> placeAutoBet(Long userId, LotteryReqVO.PlaceBet reqVO, String actor) {
        return DataPermissionUtils.executeIgnore(() -> {
            MemberDO member = requireMember(reqVO.getMemberId(), userId);
            if (!isAutoProxy(member) || !bool(member.getAutoBetEnabled())) throw exception(RECORD_NOT_FOUND);
            reqVO.setMemberId(member.getId());
            BetResult result = placeBetInternal(reqVO, actor, TYPE_AUTO_PROXY);
            if (!result.duplicate()) {
                MessageDO message = messageMapper.selectById(result.messageId());
                if (message != null) {
                    message.setCommandType("BET");
                    message.setMessageType(TYPE_AUTO_PROXY);
                    message.setReply(robotReplyTemplate.betReceipt(result.member(), result.period(), reqVO.getContent(),
                            result.periodSequence(), result.itemCount(), result.amount(), result.balance()));
                    message.setProcessedAt(LocalDateTime.now());
                    messageMapper.updateById(message);
                }
            }
            return betResultMap(result);
        });
    }

    @Override
    public Map<String, Object> prepareAutoProxyBet(Long userId, String memberId, String content) {
        return DataPermissionUtils.executeIgnore(() -> {
            MemberDO member = requireMember(memberId, userId);
            if (!isAutoProxy(member) || !bool(member.getAutoBetEnabled())) throw exception(RECORD_NOT_FOUND);
            List<LotteryBettingService.ParsedBet> parsed = bettingService.parse(content, getEffectiveOdds(userId));
            BigDecimal required = money(parsed.stream().map(LotteryBettingService.ParsedBet::amount)
                    .reduce(ZERO, BigDecimal::add));
            return map("requiredAmount", required, "balance", money(member.getBalance()), "itemCount", parsed.size());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Map<String, Object> autoTopUpProxy(Long userId, String memberId, String period, BigDecimal amount) {
        return DataPermissionUtils.executeIgnore(() -> {
            MemberDO member = requireMember(memberId, userId);
            if (!isAutoProxy(member) || !bool(member.getAutoBetEnabled()) || amount == null || amount.signum() <= 0) {
                throw exception(RECORD_NOT_FOUND);
            }
            LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
            transfer.setType("上分");
            transfer.setAmount(money(amount));
            transfer.setRemark("自动托虚拟积分不足，系统自动审核");
            String memberKey = UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
            return approveAutoProxyTransfer(member, transfer, period, "网页群",
                    "auto-proxy-topup:" + memberKey + ":" + period, "自动托:" + member.getName());
        });
    }

    private Map<String, Object> approveAutoProxyTransfer(MemberDO member, LotteryReqVO.Transfer transfer,
                                                          String period, String channel, String externalId,
                                                          String actor) {
        requireTransferType(transfer.getType());
        if (StrUtil.isNotBlank(externalId)) {
            MessageDO existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                    new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                            .eq(MessageDO::getExternalId, externalId).last("LIMIT 1")));
            if (existing != null) {
                return map("duplicate", true, "messageId", existing.getId(), "reply", existing.getReply(),
                        "balance", money(member.getBalance()), "commandType", existing.getCommandType());
            }
        }
        String recordId = id();
        BigDecimal delta = "下分".equals(transfer.getType()) ? transfer.getAmount().negate() : transfer.getAmount();
        LotteryBalanceLedgerService.BalanceChange change = balanceLedgerService.change(member, delta,
                "上分".equals(transfer.getType()) ? LotteryBalanceLedgerService.DEPOSIT
                        : LotteryBalanceLedgerService.WITHDRAW,
                recordId, actor, value(transfer.getRemark(), "自动托上下分自动审核"));
        AmountRecordDO record = new AmountRecordDO();
        record.setId(recordId);
        record.setMemberId(member.getId());
        record.setMemberName(member.getName());
        record.setType(transfer.getType());
        record.setAmount(money(transfer.getAmount()));
        record.setStatus("已通过");
        record.setRecordSource(TYPE_AUTO_PROXY);
        record.setRemark(value(transfer.getRemark(), "自动托上下分自动审核"));
        record.setAuditedAt(LocalDateTime.now());
        record.setAuditedBy(actor);
        record.setUserId(member.getUserId());
        amountRecordMapper.insert(record);

        MessageDO message = new MessageDO();
        message.setChannel(StrUtil.blankToDefault(channel, CHANNEL_WEB_GROUP));
        message.setMemberId(member.getId());
        message.setMember(member.getName());
        message.setPeriod(StrUtil.blankToDefault(period, ""));
        message.setContent(("上分".equals(transfer.getType()) ? "上" : "下")
                + robotReplyTemplate.number(transfer.getAmount()));
        message.setStatus("已通过");
        message.setExternalId(externalId);
        message.setError("");
        message.setCommandType("上分".equals(transfer.getType()) ? "DEPOSIT_REQUEST" : "WITHDRAW_REQUEST");
        message.setMessageType(TYPE_AUTO_PROXY);
        message.setReply(robotReplyTemplate.amountAudited(member.getName(), transfer.getType(),
                transfer.getAmount(), "已通过", change.after()));
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(member.getUserId());
        messageMapper.insert(message);
        return map("duplicate", false, "recordId", recordId, "messageId", message.getId(),
                "reply", message.getReply(), "balance", change.after(), "commandType", message.getCommandType());
    }

    private BetResult placeBetInternal(LotteryReqVO.PlaceBet reqVO, String actor) {
        return placeBetInternal(reqVO, actor, TYPE_PLAYER);
    }

    private BetResult placeBetInternal(LotteryReqVO.PlaceBet reqVO, String actor, String requestedOrderType) {
        MemberDO member = StrUtil.isNotBlank(reqVO.getMemberId()) ? requireMember(reqVO.getMemberId())
                : findMemberByName(reqVO.getMemberName());
        if (member == null) throw exception(MEMBER_NOT_FOUND);
        String orderType = isAutoProxy(member) ? TYPE_AUTO_PROXY : value(requestedOrderType, TYPE_PLAYER);
        if (StrUtil.isNotBlank(reqVO.getExternalId())) {
            MessageDO existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                    new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                            .eq(MessageDO::getExternalId, reqVO.getExternalId())));
            if (existing != null) {
                if (StrUtil.isNotBlank(existing.getOrderId())) return existingBetResult(existing, member);
                throw exception(EXTERNAL_MESSAGE_EXISTS);
            }
        }
        SystemStateDO state = requireState(member.getUserId());
        if (!bool(state.getRoomOpen())) throw exception(ROOM_CLOSED);
        String channel = StrUtil.blankToDefault(reqVO.getChannel(), CHANNEL_WEB_GROUP);
        if (CHANNEL_WEB_GROUP.equals(channel) && !enabled("pullEnable", member.getUserId())) throw exception(ROOM_CLOSED);

        LotteryConfigDO config = requireConfig(member.getUserId());
        if (!TYPE_AUTO_PROXY.equals(orderType) && !bool(config.getBossMode())) {
            throw exception(MARKET_ORDER_UNAVAILABLE);
        }
        if (bool(member.getWebOnly()) && !Set.of(CHANNEL_WEB_GROUP, CHANNEL_WEB_PRIVATE).contains(channel)) {
            throw exception(ROOM_CLOSED);
        }
        if ("私聊".equals(channel) && (!enabled("privateMode", member.getUserId()) || !bool(member.getPrivateChat()))) {
            throw exception(PRIVATE_BET_DISABLED);
        }
        String integrationKey = Map.of("微信", "wechat", "飞鱼", "fish", "蓝鲸", "blueWhale").get(channel);
        if (integrationKey != null) {
            IntegrationDO integration = DataPermissionUtils.executeIgnore(() -> integrationMapper.selectOne(
                    new LambdaQueryWrapper<IntegrationDO>().eq(IntegrationDO::getUserId, member.getUserId())
                            .eq(IntegrationDO::getIntegrationKey, integrationKey)));
            if (integration == null || !"已登录".equals(integration.getStatus())) throw exception(INTEGRATION_NOT_READY);
        }

        DrawDO existingDraw = DataPermissionUtils.executeIgnore(() -> drawMapper.selectOne(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, member.getUserId()).eq(DrawDO::getPeriod, reqVO.getPeriod())));
        if (existingDraw != null) throw exception(PERIOD_ALREADY_SETTLED);
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, member.getUserId()).eq(IssueDO::getPeriod, reqVO.getPeriod())));
        if (issue == null || !"OPEN".equals(issue.getStatus())) throw exception(ISSUE_NOT_OPEN);
        if (issueFreshnessPolicy.isStale(issue)) throw exception(ISSUE_SOURCE_STALE);
        List<OddDO> odds = getEffectiveOdds(member.getUserId());
        List<LotteryBettingService.ParsedBet> parsed = bettingService.parse(reqVO.getContent(), odds);
        boolean hasDragonTiger = parsed.stream().anyMatch(item -> "龙虎".equals(item.play()));
        boolean hasNormal = parsed.stream().anyMatch(item -> !"龙虎".equals(item.play()));
        int playType = value(config.getPlayType(), 2);
        if (playType == 0 && hasDragonTiger || playType == 1 && hasNormal) throw exception(PLAY_TYPE_DISABLED);
        BigDecimal total = money(parsed.stream().map(LotteryBettingService.ParsedBet::amount).reduce(ZERO, BigDecimal::add));
        if (value(member.getBalance(), ZERO).compareTo(total) < 0) throw exception(MEMBER_BALANCE_NOT_ENOUGH);

        int oldSequence = value(issue.getOrderSequence(), 0);
        int periodSequence = oldSequence + 1;
        int issueUpdated = issueMapper.update(null, new LambdaUpdateWrapper<IssueDO>()
                .eq(IssueDO::getId, issue.getId()).eq(IssueDO::getUserId, member.getUserId())
                .eq(IssueDO::getStatus, "OPEN").eq(IssueDO::getOrderSequence, oldSequence)
                .set(IssueDO::getOrderSequence, periodSequence));
        if (issueUpdated != 1) throw exception(BET_STATE_CHANGED);

        int oldMemberVersion = value(member.getVersion(), 0);
        BigDecimal balanceBefore = money(member.getBalance());
        BigDecimal balance = money(balanceBefore.subtract(total));
        BigDecimal totalBet = money(value(member.getTotalBet(), ZERO).add(total));
        int memberUpdated = memberMapper.update(null, new LambdaUpdateWrapper<MemberDO>()
                .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, member.getUserId())
                .eq(MemberDO::getVersion, oldMemberVersion).ge(MemberDO::getBalance, total)
                .set(MemberDO::getBalance, balance).set(MemberDO::getTotalBet, totalBet)
                .set(MemberDO::getVersion, oldMemberVersion + 1));
        if (memberUpdated != 1) throw exception(MEMBER_BALANCE_NOT_ENOUGH);

        OrderDO order = new OrderDO();
        order.setId(id());
        order.setMemberId(member.getId());
        order.setMemberName(member.getName());
        order.setPeriod(reqVO.getPeriod().trim());
        order.setContent(reqVO.getContent().trim());
        order.setAmount(total);
        order.setWin(ZERO);
        order.setStatus("未开奖");
        order.setSource(TYPE_AUTO_PROXY.equals(orderType) ? "自动托"
                : CHANNEL_WEB_PRIVATE.equals(channel) ? "私聊" : channel);
        order.setOrderType(orderType);
        order.setDeliveryMode("LOCAL_ONLY");
        order.setMarketStatus("NOT_REQUIRED");
        order.setMarketOrderId("");
        order.setMarketError("");
        order.setMarketAttempts(0);
        order.setPeriodSequence(periodSequence);
        order.setVersion(0);
        order.setUserId(member.getUserId());
        orderMapper.insert(order);
        member.setBalance(balance);
        member.setVersion(oldMemberVersion + 1);
        balanceLedgerService.recordAppliedChange(member, balanceBefore, balance,
                LotteryBalanceLedgerService.BET_DEBIT, order.getId(), actor,
                "期号 " + order.getPeriod() + " 下注 " + order.getContent());
        for (LotteryBettingService.ParsedBet value : parsed) {
            BetItemDO item = new BetItemDO();
            item.setId(id());
            item.setOrderId(order.getId());
            item.setPlay(value.play());
            item.setSelection(value.selection());
            item.setAmount(value.amount());
            item.setOdds(value.odds());
            item.setPayout(ZERO);
            item.setUserId(order.getUserId());
            betItemMapper.insert(item);
        }
        MessageDO message = new MessageDO();
        message.setChannel(channel);
        message.setMemberId(member.getId());
        message.setMember(member.getName());
        message.setPeriod(order.getPeriod());
        message.setContent(order.getContent());
        message.setStatus("已下单");
        message.setOrderId(order.getId());
        message.setExternalId(reqVO.getExternalId());
        message.setError("");
        message.setCommandType("BET");
        message.setMessageType(orderType);
        message.setReply("下注成功，订单号 " + order.getId());
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(member.getUserId());
        messageMapper.insert(message);
        if ("跟".equals(order.getSource())) {
            FollowOrderDO follow = new FollowOrderDO();
            follow.setId(id());
            follow.setSource(member.getName());
            follow.setTarget(order.getContent());
            follow.setRatio(BigDecimal.ONE);
            follow.setEnabled(true);
            follow.setUserId(member.getUserId());
            followOrderMapper.insert(follow);
        }
        if (!TYPE_AUTO_PROXY.equals(orderType)) {
            logAs(member.getUserId(), actor, member.getName(), "下注 " + total + "，期号 " + order.getPeriod());
        }
        return new BetResult(message.getId(), order.getId(), member.getName(), order.getPeriod(), total, balance,
                parsed.size(), periodSequence, parsed, order.getStatus(), false);
    }

    @Override
    public List<Map<String, Object>> getChimaRecords() {
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>()
                .orderByAsc(MemberDO::getId));
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 1000"));
        SystemStateDO state = first(systemStateMapper.selectList(null));
        return calculatePeriodChima(orders, members, state);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelOrder(String id) {
        return cancelResultMap(cancelOrderInternal(id, null, loginName(), false, true));
    }

    private CancelResult cancelOrderInternal(String id, String expectedMemberId, String actor,
                                             boolean requireCancelEnabled, boolean allowAutoProxy) {
        OrderDO order = orderMapper.selectById(id);
        if (order == null || expectedMemberId != null && !expectedMemberId.equals(order.getMemberId())) {
            throw exception(ORDER_NOT_FOUND);
        }
        if (!"未开奖".equals(order.getStatus())) throw exception(ORDER_CAN_NOT_CANCEL);
        if (!allowAutoProxy && isAutoProxyOrder(order)) throw exception(ORDER_CAN_NOT_CANCEL);
        if ("MARKET_ADAPTER".equals(order.getDeliveryMode())
                && Set.of("SUBMITTED", "CONFIRMED").contains(order.getMarketStatus())) throw exception(ORDER_CAN_NOT_CANCEL);
        MemberDO member = requireMember(order.getMemberId());
        if (requireCancelEnabled && !enabled("openCancel", member.getUserId())) throw exception(ORDER_CAN_NOT_CANCEL);
        order.setStatus("已退码");
        order.setCancelledAt(LocalDateTime.now());
        int orderVersion = value(order.getVersion(), 0);
        order.setVersion(orderVersion + 1);
        int cancelled = orderMapper.update(order, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, id)
                .eq(OrderDO::getUserId, order.getUserId()).eq(OrderDO::getStatus, "未开奖")
                .eq(OrderDO::getVersion, orderVersion));
        if (cancelled != 1) throw exception(ORDER_CAN_NOT_CANCEL);
        int memberVersion = value(member.getVersion(), 0);
        BigDecimal balanceBefore = money(member.getBalance());
        member.setBalance(money(balanceBefore.add(order.getAmount())));
        member.setTotalBet(money(value(member.getTotalBet(), ZERO).subtract(order.getAmount()).max(ZERO)));
        member.setVersion(memberVersion + 1);
        int refunded = memberMapper.update(member, new LambdaUpdateWrapper<MemberDO>()
                .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, member.getUserId())
                .eq(MemberDO::getVersion, memberVersion));
        if (refunded != 1) throw exception(BET_STATE_CHANGED);
        balanceLedgerService.recordAppliedChange(member, balanceBefore, member.getBalance(),
                LotteryBalanceLedgerService.BET_REFUND, order.getId(), actor, "退码订单 " + order.getId());
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                .eq(MessageDO::getOrderId, id).set(MessageDO::getStatus, "已退码"));
        logAs(member.getUserId(), actor, member.getName(), "退码订单 " + id);
        return new CancelResult(id, "已退码", money(order.getAmount()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settlePeriod(String period, LotteryReqVO.Settle reqVO) {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        return settlePeriodInternal(userId, period, reqVO, loginName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settlePeriodForUser(Long userId, String period, String result, String actor) {
        LotteryReqVO.Settle reqVO = new LotteryReqVO.Settle();
        reqVO.setResult(result);
        reqVO.setReason("开奖API二次确认后自动结算");
        return settlePeriodInternal(userId, period, reqVO, actor);
    }

    private Map<String, Object> settlePeriodInternal(Long userId, String period, LotteryReqVO.Settle reqVO, String actor) {
        if (reqVO == null || StrUtil.isBlank(reqVO.getResult())) throw exception(BET_CONTENT_INVALID);
        String normalizedResult = normalizeFiveDigitDraw(reqVO.getResult());
        if (LotteryDrawVerificationService.ZERO_RESULT.equals(normalizedResult)) throw exception(DRAW_RESULT_ABNORMAL);
        String reason = StrUtil.trim(reqVO.getReason());
        if (!"system".equals(actor) && StrUtil.isBlank(reason)) throw exception(DRAW_REASON_REQUIRED);
        boolean autoRebate = enabled("autoDiscount", userId);
        if (autoRebate) lockOwnerFinance(userId);
        if ("system".equals(actor)) {
            IssueDO verified = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                    .eq(IssueDO::getUserId, userId).eq(IssueDO::getPeriod, period).last("LIMIT 1")));
            if (verified == null || !"SETTLING".equals(verified.getStatus())
                    || value(verified.getDrawConfirmations(), 0) < 2
                    || !Objects.equals(verified.getResult(), normalizedResult)) {
                throw exception(DRAW_RESULT_NOT_VERIFIED);
            }
        }
        LotteryBettingService.DrawResult draw = bettingService.deriveDraw(normalizedResult);
        DrawDO oldDraw = DataPermissionUtils.executeIgnore(() -> drawMapper.selectOne(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, userId).eq(DrawDO::getPeriod, period)));
        if (oldDraw != null && !Objects.equals(oldDraw.getResult(), draw.result())) throw exception(PERIOD_ALREADY_SETTLED);
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getPeriod, period).eq(OrderDO::getStatus, "未开奖")
                .orderByAsc(OrderDO::getCreateTime)));
        if (oldDraw != null && orders.isEmpty()) {
            if ("system".equals(actor)) {
                IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(
                        new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getUserId, userId)
                                .eq(IssueDO::getPeriod, period).last("LIMIT 1")));
                if (issue != null && !"SETTLED".equals(issue.getStatus())) {
                    transitionIssue(issue, "SETTLED", "自动结算幂等确认",
                            map("reason", reason, "result", normalizedResult));
                    issue.setResult(normalizedResult);
                    issue.setDrawConfirmations(Math.max(2, value(issue.getDrawConfirmations(), 0)));
                    issue.setSettledAt(LocalDateTime.now());
                    issueMapper.updateById(issue);
                }
            }
            return map("period", period, "result", oldDraw.getResult(), "bigSmall", oldDraw.getBigSmall(),
                    "oddEven", oldDraw.getOddEven(), "dragonTiger", oldDraw.getDragonTiger(), "orders", 0,
                    "totalBet", ZERO, "totalPayout", ZERO, "alreadySettled", true);
        }
        BigDecimal totalAmount = ZERO;
        BigDecimal totalPayout = ZERO;
        List<Map<String, Object>> details = new ArrayList<>();
        Map<String, List<String>> winningLines = new LinkedHashMap<>();
        Map<String, BigDecimal> winningPayouts = new HashMap<>();
        Map<String, String> winningMemberIds = new HashMap<>();
        for (OrderDO order : orders) {
            List<BetItemDO> items = DataPermissionUtils.executeIgnore(() -> betItemMapper.selectList(
                    new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, userId)
                            .eq(BetItemDO::getOrderId, order.getId())));
            BigDecimal payout = ZERO;
            for (BetItemDO item : items) {
                LotteryBettingService.ParsedBet parsed = new LotteryBettingService.ParsedBet(item.getPlay(), item.getSelection(),
                        item.getAmount(), item.getOdds());
                boolean won = bettingService.isWinning(parsed, draw);
                item.setWon(won);
                item.setPayout(won ? money(item.getAmount().multiply(item.getOdds())) : ZERO);
                payout = payout.add(item.getPayout());
                betItemMapper.updateById(item);
                if (won) {
                    winningLines.computeIfAbsent(order.getMemberName(), ignored -> new ArrayList<>())
                            .add(item.getSelection() + "，套数" + robotReplyTemplate.number(item.getAmount())
                                    + "，房费" + robotReplyTemplate.number(item.getPayout()));
                    winningPayouts.merge(order.getMemberName(), item.getPayout(), BigDecimal::add);
                    winningMemberIds.put(order.getMemberName(), order.getMemberId());
                }
            }
            payout = money(payout);
            int orderVersion = value(order.getVersion(), 0);
            order.setWin(payout);
            order.setStatus(payout.signum() > 0 ? "已中奖" : "未中奖");
            order.setSettledAt(LocalDateTime.now());
            order.setVersion(orderVersion + 1);
            int settled = orderMapper.update(order, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, order.getId())
                    .eq(OrderDO::getUserId, userId).eq(OrderDO::getStatus, "未开奖")
                    .eq(OrderDO::getVersion, orderVersion));
            if (settled != 1) throw exception(BET_STATE_CHANGED);
            MemberDO member = requireMember(order.getMemberId(), userId);
            int memberVersion = value(member.getVersion(), 0);
            BigDecimal balanceBefore = money(member.getBalance());
            member.setBalance(money(balanceBefore.add(payout)));
            member.setProfitLoss(money(value(member.getProfitLoss(), ZERO).add(payout.subtract(order.getAmount()))));
            member.setVersion(memberVersion + 1);
            int memberUpdated = memberMapper.update(member, new LambdaUpdateWrapper<MemberDO>()
                    .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, userId)
                    .eq(MemberDO::getVersion, memberVersion));
            if (memberUpdated != 1) throw exception(BET_STATE_CHANGED);
            if (payout.signum() > 0) {
                balanceLedgerService.recordAppliedChange(member, balanceBefore, member.getBalance(),
                        LotteryBalanceLedgerService.PAYOUT, order.getId(), actor,
                        "期号 " + period + " 派奖");
            }
            messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                    .eq(MessageDO::getOrderId, order.getId()).set(MessageDO::getStatus, order.getStatus()));
            if (!isAutoProxyOrder(order)) {
                totalAmount = totalAmount.add(order.getAmount());
                totalPayout = totalPayout.add(payout);
                details.add(map("orderId", order.getId(), "member", order.getMemberName(), "amount", money(order.getAmount()),
                        "payout", payout, "status", order.getStatus()));
            }
        }
        DrawDO record = oldDraw;
        if (record == null) {
            record = new DrawDO();
            record.setUserId(userId);
            record.setPeriod(period);
            record.setResult(draw.result());
            record.setBigSmall(draw.bigSmall());
            record.setOddEven(draw.oddEven());
            record.setDragonTiger(draw.dragonTiger());
            record.setStatus("已结算");
            record.setSettledAt(LocalDateTime.now());
            drawMapper.insert(record);
        }
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, userId).eq(IssueDO::getPeriod, period)));
        if (issue == null) {
            issue = new IssueDO();
            issue.setUserId(userId);
            issue.setPeriod(period);
            issue.setStatus("NEW");
            issue.setRemainingSeconds(0);
            issue.setNextPeriod("");
            issue.setResult(normalizedResult);
            issue.setSource("system".equals(actor) ? "系统开奖" : "手动");
            issue.setError("");
            issue.setOrderSequence(0);
            issueMapper.insert(issue);
        }
        if (!"SETTLED".equals(issue.getStatus())) {
            transitionIssue(issue, "SETTLED", "system".equals(actor) ? "自动结算" : "手动结算",
                    map("reason", StrUtil.blankToDefault(reason, "开奖API二次确认"), "result", normalizedResult));
            issue.setResult(normalizedResult);
            issue.setDrawConfirmations("system".equals(actor) ? Math.max(2, value(issue.getDrawConfirmations(), 0)) : 0);
            issue.setSettledAt(LocalDateTime.now());
            issueMapper.updateById(issue);
        }
        for (Map.Entry<String, List<String>> entry : winningLines.entrySet()) {
            MemberDO member = requireMember(winningMemberIds.get(entry.getKey()), userId);
            BigDecimal payout = money(winningPayouts.get(entry.getKey()));
            BigDecimal afterBalance = money(member.getBalance());
            MessageDO message = new MessageDO();
            message.setChannel(CHANNEL_WEB_GROUP);
            message.setMemberId(member.getId());
            message.setMember(entry.getKey());
            message.setPeriod(period);
            message.setContent("");
            message.setStatus("已结算");
            message.setCommandType(COMMAND_SETTLEMENT);
            message.setMessageType(isAutoProxy(member) ? TYPE_AUTO_PROXY : TYPE_PLAYER);
            message.setReply(robotReplyTemplate.settlement(entry.getKey(), entry.getValue(), payout,
                    afterBalance));
            message.setProcessedAt(LocalDateTime.now());
            message.setUserId(userId);
            messageMapper.insert(message);
        }
        if (shouldPublishSettlementArtifacts(actor, !orders.isEmpty(), issue.getOpenedAt())) {
            MessageDO payoutSummary = new MessageDO();
            payoutSummary.setChannel(CHANNEL_WEB_GROUP);
            payoutSummary.setMemberId(null);
            payoutSummary.setMember("");
            payoutSummary.setPeriod(period);
            payoutSummary.setContent("");
            payoutSummary.setStatus("已结算");
            payoutSummary.setExternalId("payout-summary:" + period);
            payoutSummary.setCommandType(COMMAND_PAYOUT_SUMMARY);
            payoutSummary.setMessageType(TYPE_PLAYER);
            payoutSummary.setReply(robotReplyTemplate.payoutSummary(money(totalPayout)));
            payoutSummary.setProcessedAt(LocalDateTime.now());
            payoutSummary.setUserId(userId);
            messageMapper.insert(payoutSummary);
            logAs(userId, actor, "-", "结算期号 " + period + "，真实订单 " + details.size() + " 笔，投额 "
                    + money(totalAmount) + "，派彩 " + money(totalPayout) + "，原因 "
                    + StrUtil.blankToDefault(reason, "开奖API二次确认"));
        }
        Map<String, Object> result = map("period", period, "result", record.getResult(), "bigSmall", record.getBigSmall(),
                "oddEven", record.getOddEven(), "dragonTiger", record.getDragonTiger(), "orders", details.size(),
                "totalBet", money(totalAmount), "totalPayout", money(totalPayout), "details", details,
                "alreadySettled", false);
        if (autoRebate) result.put("rebate", applyRebatesInternal(userId, actor));
        return result;
    }

    @Override
    public Map<String, Object> getIssueStatus() {
        IssueDO current = issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .orderByDesc(IssueDO::getPeriod).last("LIMIT 1"));
        List<IssueTransitionDO> transitions = issueTransitionMapper.selectList(new LambdaQueryWrapper<IssueTransitionDO>()
                .orderByDesc(IssueTransitionDO::getCreateTime).last("LIMIT 20"));
        return map("current", current == null ? null : issueMap(current),
                "transitions", transitions.stream().map(this::issueTransitionMap).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setIssueStatus(String period, String status) {
        IssueDO issue = issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>().eq(IssueDO::getPeriod, period));
        if (issue == null) {
            issue = new IssueDO();
            issue.setPeriod(period);
            issue.setStatus("PENDING");
            issue.setRemainingSeconds(0);
            issue.setNextPeriod("");
            issue.setResult("");
            issue.setSource("手动");
            issue.setError("");
            issue.setOrderSequence(0);
            issueMapper.insert(issue);
        }
        String target = "open".equalsIgnoreCase(status) ? "OPEN" : "CLOSED";
        transitionIssue(issue, target, "后台手动");
        if ("OPEN".equals(target)) issue.setOpenedAt(LocalDateTime.now()); else issue.setClosedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        log("-", ("OPEN".equals(target) ? "开盘 " : "封盘 ") + period);
        if ("OPEN".equals(target)) publishIssueOpened(issue.getUserId(), period);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settlePendingIssues() {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        List<IssueDO> issues = DataPermissionUtils.executeIgnore(() -> issueMapper.selectList(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, userId).in(IssueDO::getStatus, "DRAWN", "SETTLING")
                .isNotNull(IssueDO::getResult).ne(IssueDO::getResult, "")
                .ne(IssueDO::getResult, LotteryDrawVerificationService.ZERO_RESULT)
                .ge(IssueDO::getDrawConfirmations, 2)
                .orderByAsc(IssueDO::getPeriod).last("LIMIT 10")));
        int count = 0;
        for (IssueDO issue : issues) {
            settlePeriodInternal(userId, issue.getPeriod(), settleRequest(issue.getResult()), loginName());
            count++;
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleMarketIssueOpened(Long userId, String period) {
        publishIssueOpened(userId, period);
    }

    @Override
    public PageResult<Map<String, Object>> getMessages(LotteryReqVO.MessagePage reqVO) {
        String period = StrUtil.trim(reqVO.getPeriod());
        String content = StrUtil.trim(reqVO.getContent());
        String nickname = StrUtil.trim(reqVO.getNickname());
        boolean nicknameCanMatchRobot = StrUtil.isNotBlank(nickname)
                && StrUtil.containsIgnoreCase("机器人", nickname);
        LambdaQueryWrapper<MessageDO> query = new LambdaQueryWrapper<MessageDO>()
                .and(wrapper -> wrapper.isNull(MessageDO::getCommandType)
                        .or().notIn(MessageDO::getCommandType, DRAW_RESULT_COMMANDS))
                .like(StrUtil.isNotBlank(period), MessageDO::getPeriod, period)
                .and(StrUtil.isNotBlank(content), wrapper -> wrapper.like(MessageDO::getContent, content)
                        .or().like(MessageDO::getReply, content))
                .like(StrUtil.isNotBlank(nickname) && !nicknameCanMatchRobot, MessageDO::getMember, nickname)
                .orderByDesc(MessageDO::getCreateTime)
                .orderByDesc(MessageDO::getId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MessageDO message : messageMapper.selectList(query)) {
            if (DRAW_RESULT_COMMANDS.contains(StrUtil.blankToDefault(message.getCommandType(), ""))) {
                continue;
            }
            for (Map<String, Object> row : messageDisplayRows(message)) {
                if (StrUtil.isNotBlank(content)
                        && !StrUtil.containsIgnoreCase(String.valueOf(row.get("content")), content)) {
                    continue;
                }
                if (StrUtil.isNotBlank(nickname)
                        && !StrUtil.containsIgnoreCase(String.valueOf(row.get("sender")), nickname)
                        && !StrUtil.containsIgnoreCase(String.valueOf(row.get("sourceMember")), nickname)) {
                    continue;
                }
                rows.add(row);
            }
        }
        int pageSize = reqVO.getPageSize();
        long requestedStart = (long) (reqVO.getPageNo() - 1) * pageSize;
        int fromIndex = (int) Math.min(requestedStart, rows.size());
        int toIndex = Math.min(fromIndex + pageSize, rows.size());
        return new PageResult<>(new ArrayList<>(rows.subList(fromIndex, toIndex)), (long) rows.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processIncomingMessage(LotteryReqVO.IncomingMessage reqVO) {
        return processIncomingMessageInternal(reqVO, loginName());
    }

    private Map<String, Object> processIncomingMessageInternal(LotteryReqVO.IncomingMessage reqVO, String actor) {
        MemberDO member = StrUtil.isNotBlank(reqVO.getMemberId()) ? requireMember(reqVO.getMemberId())
                : findMemberByName(reqVO.getMemberName());
        if (member == null) throw exception(MEMBER_NOT_FOUND);
        String content = reqVO.getContent().trim();
        if (StrUtil.isNotBlank(reqVO.getExternalId())) {
            MessageDO existing = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                    new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                            .eq(MessageDO::getExternalId, reqVO.getExternalId())));
            if (existing != null) return map("duplicate", true, "messageId", existing.getId(),
                    "orderId", existing.getOrderId(), "status", existing.getStatus(), "reply", existing.getReply(),
                    "commandType", existing.getCommandType());
        }
        LotteryRoomMessagePolicy.MessageType messageType = roomMessagePolicy.classify(content,
                getEffectiveOdds(member.getUserId()));
        if (messageType == LotteryRoomMessagePolicy.MessageType.CHAT) {
            MessageDO message = saveCommandMessage(member, reqVO, "CHAT", "");
            return map("messageId", message.getId(), "reply", "", "commandType", "CHAT");
        }
        if (Set.of("查", "余额").contains(content)) {
            List<OrderDO> activeOrders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                    new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getUserId, member.getUserId())
                            .eq(OrderDO::getMemberId, member.getId()).eq(OrderDO::getStatus, "未开奖")
                            .orderByAsc(OrderDO::getCreateTime)));
            String current = activeOrders.isEmpty() ? "目前无房源" : activeOrders.stream()
                    .map(order -> "[" + periodSuffix(order.getPeriod()) + "-" + value(order.getPeriodSequence(), 0) + "]" + order.getContent())
                    .collect(Collectors.joining("\n"));
            String reply = robotReplyTemplate.balance(member.getName(), current, member.getBalance());
            MessageDO message = saveCommandMessage(member, reqVO, "BALANCE", reply);
            return map("messageId", message.getId(), "reply", reply, "commandType", "BALANCE");
        }
        java.util.regex.Matcher amount = java.util.regex.Pattern.compile("^(上|下)(?:分)?(\\d+(?:\\.\\d+)?)$").matcher(content);
        if (amount.matches()) {
            if (!roomOperationAvailable(member.getUserId(), reqVO.getChannel())) {
                return roomClosedReply(member, reqVO);
            }
            LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
            transfer.setType("上".equals(amount.group(1)) ? "上分" : "下分");
            transfer.setAmount(new BigDecimal(amount.group(2)));
            transfer.setRemark(StrUtil.blankToDefault(reqVO.getChannel(), "消息") + "消息申请");
            if (isAutoProxy(member)) {
                return approveAutoProxyTransfer(member, transfer, reqVO.getPeriod(), reqVO.getChannel(),
                        reqVO.getExternalId(), "自动托:" + member.getName());
            }
            String recordId = createAmountRequest(member.getId(), transfer);
            String commandType = "上分".equals(transfer.getType()) ? "DEPOSIT_REQUEST" : "WITHDRAW_REQUEST";
            String reply = robotReplyTemplate.amountPending(member.getName());
            MessageDO message = saveCommandMessage(member, reqVO, commandType, reply);
            return map("messageId", message.getId(), "reply", reply, "recordId", recordId, "commandType", commandType);
        }
        java.util.regex.Matcher cancel = java.util.regex.Pattern.compile("^退(?:码)?\\s*([A-Za-z0-9_-]+)$").matcher(content);
        if (cancel.matches()) {
            String orderId = cancel.group(1);
            CancelResult result = cancelOrderInternal(orderId, member.getId(), actor, true, false);
            String reply = robotReplyTemplate.cancelSucceeded(result.id(), result.refunded());
            MessageDO message = saveCommandMessage(member, reqVO, "CANCEL", reply);
            message.setOrderId(orderId);
            messageMapper.updateById(message);
            return map("messageId", message.getId(), "orderId", orderId, "reply", reply, "commandType", "CANCEL",
                    "refunded", result.refunded());
        }
        String period = StrUtil.trim(reqVO.getPeriod());
        if (!roomOperationAvailable(member.getUserId(), reqVO.getChannel())) {
            return roomClosedReply(member, reqVO);
        }
        if (StrUtil.isBlank(period)) {
            IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                    .eq(IssueDO::getUserId, member.getUserId()).eq(IssueDO::getStatus, "OPEN")
                    .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
            if (issue == null) return roomClosedReply(member, reqVO);
            if (issueFreshnessPolicy.isStale(issue)) return drawSourceStaleReply(member, reqVO);
            period = issue.getPeriod();
        } else {
            String requestedPeriod = period;
            IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                    .eq(IssueDO::getUserId, member.getUserId()).eq(IssueDO::getPeriod, requestedPeriod)
                    .eq(IssueDO::getStatus, "OPEN").last("LIMIT 1")));
            if (issue == null) return roomClosedReply(member, reqVO);
            if (issueFreshnessPolicy.isStale(issue)) return drawSourceStaleReply(member, reqVO);
        }
        LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
        bet.setMemberId(member.getId());
        bet.setPeriod(period);
        bet.setContent(reqVO.getContent());
        bet.setChannel(reqVO.getChannel());
        bet.setExternalId(reqVO.getExternalId());
        BetResult result = placeBetInternal(bet, actor);
        String reply = robotReplyTemplate.betReceipt(result.member(), result.period(), content,
                result.periodSequence(), result.itemCount(), result.amount(), result.balance());
        MessageDO message = messageMapper.selectById(result.messageId());
        if (message != null) {
            message.setCommandType("BET");
            message.setReply(reply);
            message.setProcessedAt(LocalDateTime.now());
            messageMapper.updateById(message);
        }
        Map<String, Object> response = betResultMap(result);
        response.put("reply", reply);
        response.put("commandType", "BET");
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOdds(LotteryReqVO.Odds reqVO) {
        for (LotteryReqVO.Odd value : reqVO.getOdds()) {
            OddDO item = oddMapper.selectOne(new LambdaQueryWrapper<OddDO>().eq(OddDO::getCode, value.getId()));
            if (item == null) {
                item = new OddDO();
                item.setCode(value.getId());
                item.setPlay(value(value.getPlay(), value.getId()));
                item.setItem(value(value.getItem(), value.getPlay()));
                item.setRate(value.getRate());
                item.setSecondaryRate(value.getSecondaryRate());
                item.setMinLimit(value.getMinLimit());
                item.setMaxLimit(value.getMaxLimit());
                item.setStatus(value(value.getStatus(), "启用"));
                oddMapper.insert(item);
            } else {
                item.setPlay(value(value.getPlay(), item.getPlay()));
                item.setItem(value(value.getItem(), item.getItem()));
                item.setRate(value.getRate());
                item.setSecondaryRate(value.getSecondaryRate());
                item.setMinLimit(value.getMinLimit());
                item.setMaxLimit(value.getMaxLimit());
                item.setStatus(value(value.getStatus(), "启用"));
                oddMapper.updateById(item);
            }
        }
        log("-", "保存赔率设置");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String savePresetOrder(LotteryReqVO.PresetOrder reqVO) {
        String content = reqVO.getContent().trim();
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        List<LotteryBettingService.ParsedBet> parsed = bettingService.parse(content, getEffectiveOdds(userId));
        if (parsed.size() > 10_000) throw exception(PRESET_BET_TOO_MANY);
        PresetOrderDO item = StrUtil.isBlank(reqVO.getId()) ? null : presetOrderMapper.selectById(reqVO.getId());
        boolean create = item == null;
        if (create) {
            item = new PresetOrderDO();
            item.setId(id());
        }
        item.setMember(value(reqVO.getMember(), ""));
        item.setContent(content);
        // Legacy databases still contain this column, but preset orders no longer have an enable/disable state.
        item.setEnabled(true);
        if (create) presetOrderMapper.insert(item); else presetOrderMapper.updateById(item);
        log("-", create ? "新增预设订单" : "修改预设订单");
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePresetOrder(String id) {
        if (presetOrderMapper.selectById(id) == null) throw exception(RECORD_NOT_FOUND);
        presetOrderMapper.deleteById(id);
        log("-", "删除预设订单 " + id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String saveQuickCommand(LotteryReqVO.QuickCommand reqVO) {
        QuickCommandDO item = StrUtil.isBlank(reqVO.getId()) ? null : quickCommandMapper.selectById(reqVO.getId());
        boolean create = item == null;
        if (create) {
            item = new QuickCommandDO();
            item.setId(id());
        }
        item.setLabel(reqVO.getLabel());
        item.setContent(reqVO.getContent());
        item.setSort(reqVO.getSort());
        item.setEnabled(reqVO.getEnabled());
        if (create) quickCommandMapper.insert(item); else quickCommandMapper.updateById(item);
        log("-", create ? "新增快捷指令" : "修改快捷指令");
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuickCommand(String id) {
        if (quickCommandMapper.selectById(id) == null) throw exception(RECORD_NOT_FOUND);
        quickCommandMapper.deleteById(id);
        log("-", "删除快捷指令 " + id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String saveFollowOrder(LotteryReqVO.FollowOrder reqVO) {
        FollowOrderDO item = StrUtil.isBlank(reqVO.getId()) ? null : followOrderMapper.selectById(reqVO.getId());
        boolean create = item == null;
        if (create) {
            item = new FollowOrderDO();
            item.setId(id());
        }
        item.setSource(reqVO.getSource());
        item.setTarget(reqVO.getTarget());
        item.setRatio(reqVO.getRatio());
        item.setEnabled(reqVO.getEnabled());
        if (create) followOrderMapper.insert(item); else followOrderMapper.updateById(item);
        log("-", create ? "新增跟单" : "修改跟单");
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFollowOrder(String id) {
        if (followOrderMapper.selectById(id) == null) throw exception(RECORD_NOT_FOUND);
        followOrderMapper.deleteById(id);
        log("-", "删除跟单 " + id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMessage(Long id, String status) {
        MessageDO item = messageMapper.selectById(id);
        if (item == null) throw exception(RECORD_NOT_FOUND);
        item.setStatus(status);
        messageMapper.updateById(item);
    }

    @Override
    public Map<String, Object> getRoomSession(LotteryRoomReqVO.Credential reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            RoomAccess access = requireRoomAccess(reqVO);
            touchMemberPresence(access.member());
            return getRoomSessionInternal(access.member(), access.mode());
        });
    }

    private Map<String, Object> getRoomSessionInternal(MemberDO member, String roomMode) {
        LotteryConfigDO config = requireConfig(member.getUserId());
        SystemStateDO state = requireState(member.getUserId());
        LocalDateTime onlineCutoff = LocalDateTime.now().minus(MEMBER_ONLINE_TIMEOUT);
        long onlineMembers = DataPermissionUtils.executeIgnore(() -> memberMapper.selectList(
                        new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, member.getUserId())))
                .stream().filter(item -> !isAutoProxy(item) && isMemberOnline(item, onlineCutoff)).count();
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, member.getUserId()).eq(OrderDO::getMemberId, member.getId())
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 30")));
        List<String> orderIds = orders.stream().map(OrderDO::getId).toList();
        Map<String, List<BetItemDO>> items = orderIds.isEmpty() ? Map.of() : DataPermissionUtils.executeIgnore(() ->
                betItemMapper.selectList(new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, member.getUserId())
                        .in(BetItemDO::getOrderId, orderIds))).stream().collect(Collectors.groupingBy(BetItemDO::getOrderId));
        List<AmountRecordDO> amounts = DataPermissionUtils.executeIgnore(() -> amountRecordMapper.selectList(
                new LambdaQueryWrapper<AmountRecordDO>().eq(AmountRecordDO::getUserId, member.getUserId())
                        .eq(AmountRecordDO::getMemberId, member.getId()).orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 20")));
        List<MessageDO> messages = roomMessages(member, roomMode);
        List<DrawDO> draws = DataPermissionUtils.executeIgnore(() -> drawMapper.selectList(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, member.getUserId()).orderByDesc(DrawDO::getPeriod).last("LIMIT 40")))
                .stream().filter(item -> drawVerificationService.isTrusted(item.getResult())).limit(20).toList();
        IssueDO current = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, member.getUserId())
                .orderByDesc(IssueDO::getPeriod).orderByDesc(IssueDO::getUpdateTime).last("LIMIT 1")));
        List<IssueTransitionDO> transitions = DataPermissionUtils.executeIgnore(() ->
                issueTransitionMapper.selectList(new LambdaQueryWrapper<IssueTransitionDO>()
                        .eq(IssueTransitionDO::getUserId, member.getUserId())
                        .in(IssueTransitionDO::getToStatus, "OPEN", "CLOSED")
                        .orderByDesc(IssueTransitionDO::getCreateTime).last("LIMIT 60")));
        Map<String, Boolean> switches = DataPermissionUtils.executeIgnore(() -> switchSettingMapper.selectList(
                new LambdaQueryWrapper<SwitchSettingDO>().eq(SwitchSettingDO::getUserId, member.getUserId())))
                .stream().collect(Collectors.toMap(SwitchSettingDO::getSettingKey,
                        item -> bool(item.getEnabled()), (a, b) -> b));
        List<QuickCommandDO> commands = getEffectiveQuickCommands(member.getUserId(), true);
        String effectiveIssueStatus = issueFreshnessPolicy.effectiveStatus(current);
        return map("member", map("id", member.getId(), "name", member.getName(), "balance", money(member.getBalance()),
                        "totalBet", money(member.getTotalBet()), "profitLoss", money(member.getProfitLoss()), "avatar", member.getAvatar()),
                "room", map("name", value(config.getRoomName(), "幸运5"), "announcement", value(config.getAnnouncement(), ""),
                        "mode", roomMode, "modeName", ROOM_MODE_PRIVATE.equals(roomMode) ? "私聊" : "群聊",
                        "open", bool(state.getRoomOpen()), "online", onlineMembers,
                        "bettingEnabled", ROOM_MODE_PRIVATE.equals(roomMode)
                                || switches.getOrDefault("pullEnable", false),
                        "cancelEnabled", switches.getOrDefault("openCancel", false),
                        "features", map("groupImage", switches.getOrDefault("groupImage", false),
                                "privateImage", switches.getOrDefault("privateImage", false),
                                "prizeCard", switches.getOrDefault("prizeCard", false),
                                "imageBold", switches.getOrDefault("imageBold", false),
                                "linkToCode", switches.getOrDefault("linkToCode", false))),
                "suggestedPeriod", current == null ? "" : current.getPeriod(),
                "issue", current == null ? map("currentPeriod", "", "status", "UNAVAILABLE", "remainingSeconds", 0,
                                "nextPeriod", "", "sourceStale", false)
                        : map("currentPeriod", current.getPeriod(), "status", effectiveIssueStatus,
                                "remainingSeconds", LotteryIssueFreshnessPolicy.STATUS_SOURCE_STALE.equals(effectiveIssueStatus)
                                        ? 0 : value(current.getRemainingSeconds(), 0),
                                "nextPeriod", value(current.getNextPeriod(), ""), "serverTime", current.getServerTime(),
                                "sourceStale", LotteryIssueFreshnessPolicy.STATUS_SOURCE_STALE.equals(effectiveIssueStatus)),
                "issueTransitions", transitions.stream().sorted(Comparator.comparing(IssueTransitionDO::getCreateTime))
                        .map(item -> map("id", item.getId(), "period", item.getPeriod(), "status", item.getToStatus(),
                                "summary", value(item.getSource(), "系统") + "：" + value(item.getFromStatus(), "-")
                                        + " → " + value(item.getToStatus(), "-"), "createdAt", item.getCreateTime())).toList(),
                "draws", draws.stream().map(item -> {
                    Map<String, Object> value = drawMap(item);
                    value.put("numbers", item.getResult().replaceAll("\\D", "").chars().mapToObj(c -> String.valueOf((char) c)).toList());
                    return value;
                }).toList(),
                "orders", orders.stream().map(item -> orderMap(item, items.getOrDefault(item.getId(), List.of()))).toList(),
                "amountRecords", amounts.stream().map(this::amountRecordMap).toList(),
                "messages", messages.stream().sorted(Comparator.comparing(MessageDO::getCreateTime))
                        .map(item -> roomMessageMap(item, member)).toList(),
                "quickCommands", commands.stream().map(this::quickCommandMap).toList());
    }

    private List<MessageDO> roomMessages(MemberDO member, String roomMode) {
        List<MessageDO> ownMessages = DataPermissionUtils.executeIgnore(() -> messageMapper.selectList(
                ownRoomMessageQuery(member)
                        .eq(MessageDO::getChannel, ROOM_MODE_PRIVATE.equals(roomMode)
                                ? CHANNEL_WEB_PRIVATE : CHANNEL_WEB_GROUP)
                        .orderByDesc(MessageDO::getCreateTime).last("LIMIT 80")));
        if (ROOM_MODE_PRIVATE.equals(roomMode)) {
            List<MessageDO> settlements = DataPermissionUtils.executeIgnore(() -> messageMapper.selectList(
                    ownRoomMessageQuery(member).eq(MessageDO::getCommandType, COMMAND_SETTLEMENT)
                            .orderByDesc(MessageDO::getCreateTime).last("LIMIT 30")));
            return mergeMessages(ownMessages, settlements, 80);
        }
        List<MessageDO> sharedMessages = DataPermissionUtils.executeIgnore(() -> messageMapper.selectList(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                        .eq(MessageDO::getChannel, CHANNEL_WEB_GROUP)
                        .in(MessageDO::getCommandType, "BET", "CHAT", COMMAND_SETTLEMENT,
                                COMMAND_PAYOUT_SUMMARY)
                        .orderByDesc(MessageDO::getCreateTime).last("LIMIT 100")));
        return mergeMessages(ownMessages, sharedMessages, 100);
    }

    private LambdaQueryWrapper<MessageDO> ownRoomMessageQuery(MemberDO member) {
        return new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                .and(wrapper -> wrapper.eq(MessageDO::getMemberId, member.getId())
                        .or(nested -> nested.isNull(MessageDO::getMemberId)
                                .eq(MessageDO::getMember, member.getName())));
    }

    private List<MessageDO> mergeMessages(List<MessageDO> first, List<MessageDO> second, int limit) {
        Map<Long, MessageDO> byId = new LinkedHashMap<>();
        first.forEach(item -> byId.put(item.getId(), item));
        second.forEach(item -> byId.put(item.getId(), item));
        return byId.values().stream().sorted(Comparator.comparing(MessageDO::getCreateTime).reversed())
                .limit(limit).toList();
    }

    private Map<String, Object> roomMessageMap(MessageDO item, MemberDO member) {
        Map<String, Object> result = messageMap(item);
        boolean own = Objects.equals(item.getMemberId(), member.getId())
                || item.getMemberId() == null && Objects.equals(item.getMember(), member.getName());
        boolean sharedPayoutSummary = COMMAND_PAYOUT_SUMMARY.equals(item.getCommandType());
        boolean sharedSettlement = COMMAND_SETTLEMENT.equals(item.getCommandType());
        String sharedReply = sharedPayoutSummary || sharedSettlement ? item.getReply() : "";
        result.put("own", own);
        if (!own) {
            result.put("orderId", "");
            result.put("error", "");
            result.put("reply", "BET".equals(item.getCommandType())
                    ? robotReplyTemplate.publicBetReceipt(item.getMember(), item.getReply())
                    : sharedReply);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> roomPlaceBet(LotteryRoomReqVO.Bet reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            RoomAccess access = requireRoomAccess(reqVO);
            MemberDO member = access.member();
            LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
            bet.setMemberId(member.getId());
            bet.setPeriod(reqVO.getPeriod());
            bet.setContent(reqVO.getContent());
            bet.setChannel(access.channel());
            bet.setExternalId(StrUtil.isBlank(reqVO.getExternalId()) ? null
                    : "room:" + access.mode() + ":" + member.getId() + ":" + reqVO.getExternalId());
            return betResultMap(placeBetInternal(bet, member.getName()));
        });
    }

    @Override
    public Map<String, Object> previewRoomBet(LotteryRoomReqVO.PreviewBet reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomAccess(reqVO).member();
            List<LotteryBettingService.ParsedBet> items = bettingService.parse(reqVO.getContent(),
                    getEffectiveOdds(member.getUserId()));
            return map("count", items.size(), "total", money(items.stream().map(LotteryBettingService.ParsedBet::amount)
                    .reduce(ZERO, BigDecimal::add)), "selections", items.stream().map(LotteryBettingService.ParsedBet::selection).toList());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processRoomMessage(LotteryRoomReqVO.Message reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            RoomAccess access = requireRoomAccess(reqVO);
            MemberDO member = access.member();
            LotteryReqVO.IncomingMessage message = new LotteryReqVO.IncomingMessage();
            message.setMemberId(member.getId());
            message.setMemberName(member.getName());
            message.setPeriod(reqVO.getPeriod());
            message.setContent(reqVO.getContent());
            message.setChannel(access.channel());
            message.setExternalId(StrUtil.isBlank(reqVO.getExternalId()) ? null
                    : "room:" + access.mode() + ":" + member.getId() + ":" + reqVO.getExternalId());
            return processIncomingMessageInternal(message, member.getName());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRoomAmountRequest(LotteryRoomReqVO.Amount reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            RoomAccess access = requireRoomAccess(reqVO);
            MemberDO member = access.member();
            requireRoomOperation(member.getUserId(), access.channel());
            LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
            transfer.setType(reqVO.getType());
            transfer.setAmount(reqVO.getAmount());
            transfer.setRemark(reqVO.getRemark());
            if (isAutoProxy(member)) {
                return approveAutoProxyTransfer(member, transfer, "", access.channel(), null,
                        "自动托:" + member.getName());
            }
            return map("id", createAmountRequest(member.getId(), transfer));
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelRoomOrder(String orderId, LotteryRoomReqVO.Credential reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomAccess(reqVO).member();
            return cancelResultMap(cancelOrderInternal(orderId, member.getId(), member.getName(), true, false));
        });
    }

    private RoomAccess requireRoomAccess(LotteryRoomReqVO.Credential reqVO) {
        MemberDO member = memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getOpenId, reqVO.getOpenId()));
        if (member == null) throw exception(ROOM_CREDENTIAL_INVALID);
        if (enabled("enableFingerCheck", member.getUserId()) && StrUtil.isNotBlank(member.getFingerprint())
                && !Objects.equals(member.getFingerprint(), reqVO.getFp())) throw exception(ROOM_CREDENTIAL_INVALID);
        if (enabled("enableFingerCheck", member.getUserId()) && StrUtil.isBlank(member.getFingerprint()) && StrUtil.isNotBlank(reqVO.getFp())) {
            member.setFingerprint(reqVO.getFp());
            memberMapper.updateById(member);
        }
        LinkConfigDO links = ownerLinkConfig(member.getUserId());
        boolean groupEnabled = links == null || links.getGroupLinkEnabled() == null || bool(links.getGroupLinkEnabled());
        boolean privateEnabled = links == null || links.getPrivateLinkEnabled() == null || bool(links.getPrivateLinkEnabled());
        String defaultMode = links == null ? ROOM_MODE_GROUP : value(links.getDefaultRoomMode(), ROOM_MODE_GROUP);
        if (ROOM_MODE_GROUP.equals(defaultMode) && !groupEnabled) defaultMode = ROOM_MODE_PRIVATE;
        if (ROOM_MODE_PRIVATE.equals(defaultMode) && !privateEnabled) defaultMode = ROOM_MODE_GROUP;
        String roomMode = StrUtil.blankToDefault(reqVO.getRoomMode(), defaultMode);
        if (ROOM_MODE_GROUP.equals(roomMode) && !groupEnabled
                || ROOM_MODE_PRIVATE.equals(roomMode) && !privateEnabled) {
            throw exception(ROOM_MODE_DISABLED);
        }
        return new RoomAccess(member, roomMode);
    }

    private void requireRoomOperation(Long userId, String channel) {
        if (!roomOperationAvailable(userId, channel)) {
            throw exception(ROOM_CLOSED);
        }
    }

    private boolean roomOperationAvailable(Long userId, String channel) {
        return bool(requireState(userId).getRoomOpen())
                && (!CHANNEL_WEB_GROUP.equals(StrUtil.blankToDefault(channel, CHANNEL_WEB_GROUP))
                || enabled("pullEnable", userId));
    }

    private Map<String, Object> roomClosedReply(MemberDO member, LotteryReqVO.IncomingMessage reqVO) {
        String reply = robotReplyTemplate.roomClosed(member.getName());
        MessageDO message = saveCommandMessage(member, reqVO, "ROOM_CLOSED", reply);
        message.setStatus("已拒绝");
        message.setError("当前未开盘");
        messageMapper.updateById(message);
        return map("messageId", message.getId(), "reply", reply, "commandType", "ROOM_CLOSED");
    }

    private Map<String, Object> drawSourceStaleReply(MemberDO member, LotteryReqVO.IncomingMessage reqVO) {
        String reply = robotReplyTemplate.drawSourceStale(member.getName());
        MessageDO message = saveCommandMessage(member, reqVO, "SOURCE_STALE", reply);
        message.setStatus("已拒绝");
        message.setError("开奖数据已过期");
        messageMapper.updateById(message);
        return map("messageId", message.getId(), "reply", reply, "commandType", "SOURCE_STALE");
    }

    private void updateAmountRequestReply(AmountRecordDO record, MemberDO member) {
        String commandType = "上分".equals(record.getType()) ? "DEPOSIT_REQUEST" : "WITHDRAW_REQUEST";
        LocalDateTime createdAt = record.getCreateTime();
        MessageDO related = DataPermissionUtils.executeIgnore(() -> messageMapper.selectOne(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, record.getUserId())
                        .eq(MessageDO::getMember, record.getMemberName())
                        .eq(MessageDO::getCommandType, commandType)
                        .ge(createdAt != null, MessageDO::getCreateTime,
                                createdAt == null ? null : createdAt.minusSeconds(10))
                        .le(createdAt != null, MessageDO::getCreateTime,
                                createdAt == null ? null : createdAt.plusSeconds(10))
                        .orderByDesc(MessageDO::getCreateTime).last("LIMIT 1")));
        if (related == null) {
            return;
        }
        related.setReply(robotReplyTemplate.amountAudited(record.getMemberName(), record.getType(),
                record.getAmount(), record.getStatus(), member.getBalance()));
        related.setStatus(record.getStatus());
        related.setProcessedAt(LocalDateTime.now());
        messageMapper.updateById(related);
    }

    private void requireTransferType(String type) {
        if (!Set.of("上分", "下分").contains(type)) {
            throw exception(RECORD_NOT_FOUND);
        }
    }

    private void requireIntegrationKey(String key) {
        if (!INTEGRATION_KEYS.contains(key)) {
            throw exception(RECORD_NOT_FOUND);
        }
    }

    private void publishIssueOpened(Long userId, String period) {
        eventPublisher.publishEvent(new LotteryIssueOpenedEvent(TenantContextHolder.getRequiredTenantId(), userId, period));
    }

    private void lockOwnerFinance(Long userId) {
        requireState(userId);
        SystemStateDO locked = DataPermissionUtils.executeIgnore(() -> systemStateMapper.selectOne(
                new LambdaQueryWrapper<SystemStateDO>().eq(SystemStateDO::getUserId, userId)
                        .last("LIMIT 1 FOR UPDATE")));
        if (locked == null) {
            throw exception(BET_STATE_CHANGED);
        }
    }

    private MessageDO saveCommandMessage(MemberDO member, LotteryReqVO.IncomingMessage reqVO, String type, String reply) {
        MessageDO message = new MessageDO();
        message.setChannel(reqVO.getChannel());
        message.setMemberId(member.getId());
        message.setMember(member.getName());
        message.setPeriod(value(reqVO.getPeriod(), ""));
        message.setContent(reqVO.getContent());
        message.setStatus("已处理");
        message.setExternalId(reqVO.getExternalId());
        message.setError("");
        message.setCommandType(type);
        message.setMessageType(isAutoProxy(member) ? TYPE_AUTO_PROXY : TYPE_PLAYER);
        message.setReply(reply);
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(member.getUserId());
        messageMapper.insert(message);
        return message;
    }

    private MemberDO findMemberByName(String name) {
        if (StrUtil.isBlank(name)) return null;
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        return DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>()
                .eq(MemberDO::getUserId, userId)
                .and(wrapper -> wrapper.eq(MemberDO::getName, name).or().eq(MemberDO::getExternalNickname, name))
                .last("LIMIT 1")));
    }

    private BetResult existingBetResult(MessageDO message, MemberDO member) {
        OrderDO order = DataPermissionUtils.executeIgnore(() -> orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, member.getUserId()).eq(OrderDO::getId, message.getOrderId())));
        if (order == null) throw exception(EXTERNAL_MESSAGE_EXISTS);
        List<BetItemDO> items = DataPermissionUtils.executeIgnore(() -> betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().eq(BetItemDO::getUserId, member.getUserId())
                        .eq(BetItemDO::getOrderId, order.getId())));
        List<LotteryBettingService.ParsedBet> parsed = items.stream().map(item -> new LotteryBettingService.ParsedBet(
                item.getPlay(), item.getSelection(), item.getAmount(), item.getOdds())).toList();
        return new BetResult(message.getId(), order.getId(), member.getName(), order.getPeriod(), money(order.getAmount()),
                money(member.getBalance()), parsed.size(), value(order.getPeriodSequence(), 0), parsed,
                order.getStatus(), true);
    }

    private Map<String, Object> betResultMap(BetResult result) {
        return map("duplicate", result.duplicate(), "messageId", result.messageId(), "orderId", result.orderId(),
                "member", result.member(), "period", result.period(), "amount", result.amount(), "balance", result.balance(),
                "itemCount", result.itemCount(), "periodSequence", result.periodSequence(), "status", result.status(),
                "deliveryMode", "LOCAL_ONLY", "marketStatus", "NOT_REQUIRED",
                "items", result.items().stream().limit(20).map(item -> map("play", item.play(),
                        "selection", item.selection(), "amount", item.amount(), "odds", item.odds())).toList());
    }

    private Map<String, Object> cancelResultMap(CancelResult result) {
        return map("id", result.id(), "status", result.status(), "refunded", result.refunded());
    }

    private String periodSuffix(String period) {
        if (period == null) return "";
        return period.length() <= 3 ? period : period.substring(period.length() - 3);
    }

    private String balanceBusinessLabel(String type) {
        return switch (value(type, "")) {
            case LotteryBalanceLedgerService.OPENING_BALANCE -> "初始积分";
            case LotteryBalanceLedgerService.MANUAL_ADJUSTMENT -> "人工调整";
            case LotteryBalanceLedgerService.DEPOSIT -> "上分";
            case LotteryBalanceLedgerService.WITHDRAW -> "下分";
            case LotteryBalanceLedgerService.BET_DEBIT -> "下注扣款";
            case LotteryBalanceLedgerService.BET_REFUND -> "退码退款";
            case LotteryBalanceLedgerService.PAYOUT -> "开奖派奖";
            case LotteryBalanceLedgerService.REBATE -> "返水";
            default -> value(type, "未知");
        };
    }

    private List<Map<String, Object>> calculatePeriodChima(List<OrderDO> orders, List<MemberDO> members,
                                                            SystemStateDO state) {
        LocalDateTime clearedAt = state == null ? null : state.getChimaClearedAt();
        return chimaCalculator.calculate(orders, members, clearedAt).stream()
                .map(item -> map("periods", item.period(), "fakeAmount", item.fakeAmount(),
                        "totalWin", item.totalWin(), "net", item.net())).toList();
    }

    private LotteryConfigDO requireConfig() {
        return requireConfig(Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID));
    }

    private LotteryConfigDO requireConfig(Long userId) {
        LotteryConfigDO item = findUserConfig(userId);
        if (item != null) return item;
        item = new LotteryConfigDO();
        item.setUserId(userId);
        lotteryConfigMapper.insert(item);
        return lotteryConfigMapper.selectById(item.getId());
    }

    private LotteryConfigDO findUserConfig(Long userId) {
        if (userId == null) return null;
        return lotteryConfigMapper.selectOne(new LambdaQueryWrapper<LotteryConfigDO>()
                .eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1"));
    }

    private MarketConnectionDO findUserMarket(Long userId) {
        if (userId == null) return null;
        return marketConnectionMapper.selectOne(new LambdaQueryWrapper<MarketConnectionDO>()
                .eq(MarketConnectionDO::getUserId, userId).last("LIMIT 1"));
    }

    private List<OddDO> getEffectiveOdds(Long userId) {
        Long ownerUserId = Objects.requireNonNullElse(userId, DEFAULT_OWNER_USER_ID);
        List<OddDO> result = DataPermissionUtils.executeIgnore(() -> oddMapper.selectList(
                new LambdaQueryWrapper<OddDO>().eq(OddDO::getUserId, ownerUserId).orderByAsc(OddDO::getId)));
        if (!result.isEmpty() || Objects.equals(ownerUserId, DEFAULT_OWNER_USER_ID)) return result;
        return DataPermissionUtils.executeIgnore(() -> oddMapper.selectList(
                new LambdaQueryWrapper<OddDO>().eq(OddDO::getUserId, DEFAULT_OWNER_USER_ID)
                        .orderByAsc(OddDO::getId)));
    }

    private List<QuickCommandDO> getEffectiveQuickCommands(Long userId, boolean enabledOnly) {
        Long ownerUserId = Objects.requireNonNullElse(userId, DEFAULT_OWNER_USER_ID);
        List<QuickCommandDO> result = findQuickCommands(ownerUserId, enabledOnly);
        if (!result.isEmpty() || Objects.equals(ownerUserId, DEFAULT_OWNER_USER_ID)) return result;
        return findQuickCommands(DEFAULT_OWNER_USER_ID, enabledOnly);
    }

    private List<QuickCommandDO> findQuickCommands(Long userId, boolean enabledOnly) {
        return DataPermissionUtils.executeIgnore(() -> quickCommandMapper.selectList(
                new LambdaQueryWrapper<QuickCommandDO>().eq(QuickCommandDO::getUserId, userId)
                        .eq(enabledOnly, QuickCommandDO::getEnabled, true)
                        .orderByAsc(QuickCommandDO::getSort).orderByAsc(QuickCommandDO::getId)));
    }

    private SystemStateDO requireState() {
        SystemStateDO item = first(systemStateMapper.selectList(null));
        if (item != null) return item;
        item = new SystemStateDO();
        item.setOperatorUsername(loginName());
        item.setExpireAt(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        item.setRoomOpen(false);
        item.setOnline(0);
        systemStateMapper.insert(item);
        return item;
    }

    private SystemStateDO requireState(Long userId) {
        SystemStateDO item = DataPermissionUtils.executeIgnore(() -> systemStateMapper.selectOne(
                new LambdaQueryWrapper<SystemStateDO>().eq(SystemStateDO::getUserId, userId).last("LIMIT 1")));
        if (item != null) return item;
        item = new SystemStateDO();
        item.setUserId(userId);
        item.setOperatorUsername(String.valueOf(userId));
        item.setExpireAt(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        item.setRoomOpen(false);
        item.setOnline(0);
        systemStateMapper.insert(item);
        return item;
    }

    private MemberDO requireMember(String id) {
        MemberDO member = memberMapper.selectById(id);
        if (member == null) throw exception(MEMBER_NOT_FOUND);
        return member;
    }

    private MemberDO requireMember(String id, Long userId) {
        MemberDO member = DataPermissionUtils.executeIgnore(() -> memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getId, id).eq(MemberDO::getUserId, userId)));
        if (member == null) throw exception(MEMBER_NOT_FOUND);
        return member;
    }

    private LotteryReqVO.Settle settleRequest(String result) {
        LotteryReqVO.Settle reqVO = new LotteryReqVO.Settle();
        reqVO.setResult(result);
        reqVO.setReason("补跑已确认的API开奖");
        return reqVO;
    }

    private String normalizeFiveDigitDraw(String raw) {
        String normalized = raw == null ? "" : raw.replaceAll("[,，\\s]", "");
        if (!normalized.matches("\\d{5}")) throw exception(DRAW_RESULT_INVALID);
        return normalized;
    }

    private boolean enabled(String key) {
        SwitchSettingDO setting = switchSettingMapper.selectOne(new LambdaQueryWrapper<SwitchSettingDO>()
                .eq(SwitchSettingDO::getSettingKey, key));
        return setting != null && bool(setting.getEnabled());
    }

    private boolean enabled(String key, Long userId) {
        SwitchSettingDO setting = DataPermissionUtils.executeIgnore(() -> switchSettingMapper.selectOne(
                new LambdaQueryWrapper<SwitchSettingDO>().eq(SwitchSettingDO::getUserId, userId)
                        .eq(SwitchSettingDO::getSettingKey, key)));
        return setting != null && bool(setting.getEnabled());
    }

    private void transitionIssue(IssueDO issue, String target, String source) {
        transitionIssue(issue, target, source, Map.of());
    }

    private void transitionIssue(IssueDO issue, String target, String source, Map<String, Object> detail) {
        String old = issue.getStatus();
        if (Objects.equals(old, target)) return;
        IssueTransitionDO transition = new IssueTransitionDO();
        transition.setPeriod(issue.getPeriod());
        transition.setFromStatus(value(old, ""));
        transition.setToStatus(target);
        transition.setSource(source);
        transition.setDetail(JSONUtil.toJsonStr(detail == null ? Map.of() : detail));
        transition.setUserId(issue.getUserId());
        issueTransitionMapper.insert(transition);
        issue.setStatus(target);
    }

    private Map<String, Object> issueMap(IssueDO item) {
        return map("period", item.getPeriod(), "status", item.getStatus(), "marketStatus", item.getMarketStatus(),
                "remainingSeconds", value(item.getRemainingSeconds(), 0), "serverTime", date(item.getServerTime()),
                "nextPeriod", value(item.getNextPeriod(), ""), "result", value(item.getResult(), ""),
                "drawConfirmations", value(item.getDrawConfirmations(), 0), "drawFirstSeenAt", date(item.getDrawFirstSeenAt()),
                "source", value(item.getSource(), ""), "error", value(item.getError(), ""), "updatedAt", date(item.getUpdateTime()));
    }

    private Map<String, Object> issueTransitionMap(IssueTransitionDO item) {
        return map("id", item.getId(), "period", item.getPeriod(), "fromStatus", item.getFromStatus(),
                "toStatus", item.getToStatus(), "source", item.getSource(), "detail", item.getDetail(),
                "createdAt", date(item.getCreateTime()));
    }

    private Map<String, String> linkPayload(MemberDO member, String origin) {
        String base = StrUtil.removeSuffix(StrUtil.blankToDefault(origin, "http://localhost:8080"), "/");
        String tenantId = String.valueOf(TenantContextHolder.getRequiredTenantId());
        LinkConfigDO links = ownerLinkConfig(member.getUserId());
        boolean groupEnabled = links == null || links.getGroupLinkEnabled() == null || bool(links.getGroupLinkEnabled());
        boolean privateEnabled = links == null || links.getPrivateLinkEnabled() == null || bool(links.getPrivateLinkEnabled());
        String defaultMode = links == null ? ROOM_MODE_GROUP : value(links.getDefaultRoomMode(), ROOM_MODE_GROUP);
        if (ROOM_MODE_GROUP.equals(defaultMode) && !groupEnabled) defaultMode = ROOM_MODE_PRIVATE;
        if (ROOM_MODE_PRIVATE.equals(defaultMode) && !privateEnabled) defaultMode = ROOM_MODE_GROUP;
        String groupUrl = groupEnabled ? base + "/g/" + member.getOpenId() + "?tenantId=" + tenantId : "";
        String privateUrl = privateEnabled ? base + "/p/" + member.getOpenId() + "?tenantId=" + tenantId : "";
        String defaultUrl = ROOM_MODE_PRIVATE.equals(defaultMode) ? privateUrl : groupUrl;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("roomUrl", defaultUrl);
        result.put("longUrl", defaultUrl);
        result.put("shortUrl", defaultUrl);
        result.put("qrText", defaultUrl);
        result.put("groupUrl", groupUrl);
        result.put("privateUrl", privateUrl);
        result.put("defaultMode", defaultMode);
        result.put("openId", member.getOpenId());
        return result;
    }

    private LinkConfigDO ownerLinkConfig(Long userId) {
        return DataPermissionUtils.executeIgnore(() -> linkConfigMapper.selectOne(
                new LambdaQueryWrapper<LinkConfigDO>().eq(LinkConfigDO::getUserId, userId).last("LIMIT 1")));
    }

    private void log(String member, String action) {
        logAs(loginName(), member, action);
    }

    private void log(Long userId, String member, String action) {
        logAs(userId, loginName(), member, action);
    }

    private void logAs(String operator, String member, String action) {
        logAs(SecurityFrameworkUtils.getLoginUserId(), operator, member, action);
    }

    private void logAs(Long userId, String operator, String member, String action) {
        OperationLogDO item = new OperationLogDO();
        item.setUserId(Objects.requireNonNullElse(userId, DEFAULT_OWNER_USER_ID));
        item.setOperator(value(operator, "system"));
        item.setMember(value(member, "-"));
        item.setAction(action);
        operationLogMapper.insert(item);
    }

    private void verifyCurrentPassword(String password) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        AdminUserDO user = userId == null ? null : adminUserService.getUser(userId);
        if (user == null || StrUtil.isBlank(password) || !adminUserService.isPasswordMatch(password, user.getPassword())) {
            throw exception(PASSWORD_INVALID);
        }
    }

    private String loginName() {
        String nickname = SecurityFrameworkUtils.getLoginUserNickname();
        if (StrUtil.isNotBlank(nickname)) return nickname;
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }

    private boolean has(String permission) {
        return securityFrameworkService.hasPermission(permission);
    }

    private boolean hasAny(String... permissions) {
        return securityFrameworkService.hasAnyPermissions(permissions);
    }

    private String id() {
        return IdUtil.fastSimpleUUID();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String date(LocalDateTime value) {
        return value == null ? "" : DATE_FORMAT.format(value);
    }

    private String memberType(MemberDO member) {
        if (MEMBER_BOT.equalsIgnoreCase(value(member.getMemberType(), "")) || bool(member.getAutoProxy())) {
            return MEMBER_BOT;
        }
        return MEMBER_REAL;
    }

    private boolean isAutoProxy(MemberDO member) {
        return member != null && MEMBER_BOT.equals(memberType(member));
    }

    private boolean isAutoProxyOrder(OrderDO order) {
        return order != null && (TYPE_AUTO_PROXY.equals(value(order.getOrderType(), TYPE_PLAYER))
                || "自动托".equals(order.getSource()));
    }

    private void touchMemberPresence(MemberDO member) {
        if (member == null || isAutoProxy(member)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (member.getLastSeenAt() != null
                && !member.getLastSeenAt().isBefore(now.minus(MEMBER_HEARTBEAT_WRITE_INTERVAL))) {
            return;
        }
        DataPermissionUtils.executeIgnore(() -> memberMapper.update(null, new LambdaUpdateWrapper<MemberDO>()
                .eq(MemberDO::getUserId, member.getUserId())
                .eq(MemberDO::getId, member.getId())
                .set(MemberDO::getLastSeenAt, now)
                .set(MemberDO::getStatus, "在线")));
        member.setLastSeenAt(now);
        member.setStatus("在线");
    }

    private boolean isMemberOnline(MemberDO member, LocalDateTime cutoff) {
        return member != null && member.getLastSeenAt() != null && !member.getLastSeenAt().isBefore(cutoff);
    }

    static boolean shouldPublishSettlementArtifacts(String actor, boolean hasOrders, LocalDateTime openedAt) {
        return !"system".equals(actor) || hasOrders || openedAt != null;
    }

    private boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private <T> T value(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private <T> T first(List<T> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
