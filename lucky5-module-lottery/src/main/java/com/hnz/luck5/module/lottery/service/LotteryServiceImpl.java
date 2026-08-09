package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private record BetResult(Long messageId, String orderId, String member, String period, BigDecimal amount,
                             BigDecimal balance, int itemCount, int periodSequence,
                             List<LotteryBettingService.ParsedBet> items, String status, boolean duplicate) {
    }

    private record CancelResult(String id, String status, BigDecimal refunded) {
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
    @Resource private ChimaRecordMapper chimaRecordMapper;
    @Resource private LotteryBettingService bettingService;
    @Resource private SecurityFrameworkService securityFrameworkService;
    @Resource private AdminUserService adminUserService;

    @Override
    public Map<String, Object> getBootstrap() {
        Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
        LotteryConfigDO config = findUserConfig(loginUserId);
        SystemStateDO state = first(systemStateMapper.selectList(null));
        MarketConnectionDO market = findUserMarket(loginUserId);
        LinkConfigDO links = first(linkConfigMapper.selectList(null));
        ChimaConfigDO chimaConfig = first(chimaConfigMapper.selectList(null));
        List<SwitchSettingDO> switches = switchSettingMapper.selectList(new LambdaQueryWrapper<SwitchSettingDO>()
                .orderByAsc(SwitchSettingDO::getSettingKey));
        List<IntegrationDO> integrations = integrationMapper.selectList(new LambdaQueryWrapper<IntegrationDO>()
                .orderByAsc(IntegrationDO::getIntegrationKey));
        List<OddDO> odds = getEffectiveOdds(loginUserId);
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>().orderByAsc(MemberDO::getId));
        List<AmountRecordDO> amountRecords = amountRecordMapper.selectList(new LambdaQueryWrapper<AmountRecordDO>()
                .orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 500"));
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .orderByDesc(OrderDO::getCreateTime).last("LIMIT 1000"));
        Set<String> orderIds = orders.stream().map(OrderDO::getId).collect(Collectors.toSet());
        List<BetItemDO> items = orderIds.isEmpty() ? List.of() : betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, orderIds));
        Map<String, List<BetItemDO>> itemsByOrder = items.stream().collect(Collectors.groupingBy(BetItemDO::getOrderId));
        List<DrawDO> draws = drawMapper.selectList(new LambdaQueryWrapper<DrawDO>()
                .orderByDesc(DrawDO::getSettledAt).last("LIMIT 500"));
        List<PresetOrderDO> presets = presetOrderMapper.selectList(new LambdaQueryWrapper<PresetOrderDO>()
                .orderByAsc(PresetOrderDO::getId));
        List<QuickCommandDO> commands = getEffectiveQuickCommands(loginUserId, false);
        List<FollowOrderDO> follows = followOrderMapper.selectList(new LambdaQueryWrapper<FollowOrderDO>()
                .orderByDesc(FollowOrderDO::getCreateTime));
        List<OperationLogDO> operators = operationLogMapper.selectList(new LambdaQueryWrapper<OperationLogDO>()
                .orderByDesc(OperationLogDO::getCreateTime).last("LIMIT 1000"));
        List<MessageDO> messages = messageMapper.selectList(new LambdaQueryWrapper<MessageDO>()
                .orderByDesc(MessageDO::getCreateTime).last("LIMIT 1000"));
        List<RebateRecordDO> rebates = rebateRecordMapper.selectList(null);

        Map<String, Object> result = new LinkedHashMap<>();
        boolean dashboard = has("lottery:dashboard:query");
        result.put("operator", dashboard ? map("username", state == null ? loginName() : state.getOperatorUsername(),
                "expireAt", state == null ? "" : date(state.getExpireAt())) : map("username", "", "expireAt", ""));
        result.put("room", dashboard ? map("open", state != null && bool(state.getRoomOpen()),
                "online", state == null ? 0 : value(state.getOnline(), 0)) : map("open", false, "online", 0));
        result.put("dashboardStats", dashboard ? map("totalMembers", members.size(),
                "onlineMembers", members.stream().filter(item -> "在线".equals(item.getStatus())).count(),
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
        result.put("links", has("lottery:link:manage") ? map("shortUrlMode", links == null ? 2 : links.getShortUrlMode(),
                "bound", links != null) : Map.of());
        result.put("odds", has("lottery:odds:manage") ? odds.stream().map(this::oddMap).toList() : List.of());

        Map<String, BigDecimal[]> betBases = calculateBetBases(orders, itemsByOrder);
        Map<String, BigDecimal[]> rebateUsed = calculateRebateUsage(rebates);
        result.put("members", hasAny("lottery:member:manage", "lottery:rebate:manage")
                ? members.stream().map(item -> memberMap(item, betBases, rebateUsed)).toList() : List.of());
        result.put("amountRecords", has("lottery:amount:manage") ? amountRecords.stream().map(this::amountRecordMap).toList() : List.of());
        result.put("orders", hasAny("lottery:order:manage", "lottery:history:query")
                ? orders.stream().map(item -> orderMap(item, itemsByOrder.getOrDefault(item.getId(), List.of()))).toList() : List.of());
        result.put("drawHistory", has("lottery:draw:manage") ? draws.stream().map(this::drawMap).toList() : List.of());
        result.put("fakeOrders", has("lottery:preset:manage") ? presets.stream().map(item -> presetMap(item, odds)).toList() : List.of());
        result.put("quickCommands", has("lottery:quick-command:manage") ? commands.stream().map(this::quickCommandMap).toList() : List.of());
        result.put("followOrders", has("lottery:follow:manage") ? follows.stream().map(this::followMap).toList() : List.of());
        result.put("operators", has("lottery:operator:query") ? operators.stream().map(this::operationMap).toList() : List.of());
        result.put("messages", has("lottery:message:manage") ? messages.stream().map(this::messageMap).toList() : List.of());
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

    private Map<String, Object> memberMap(MemberDO item, Map<String, BigDecimal[]> bases, Map<String, BigDecimal[]> used) {
        BigDecimal[] basis = bases.getOrDefault(item.getId(), new BigDecimal[]{ZERO, ZERO});
        BigDecimal[] consumed = used.getOrDefault(item.getId(), new BigDecimal[]{ZERO, ZERO});
        BigDecimal normalPending = basis[0].subtract(consumed[0]).max(ZERO);
        BigDecimal dragonPending = basis[1].subtract(consumed[1]).max(ZERO);
        Map<String, Object> result = map("id", item.getId(), "name", item.getName(), "balance", money(item.getBalance()),
                "status", item.getStatus(), "partner", item.getPartner(), "normalRate", money(item.getNormalRate()),
                "lhhRate", money(item.getLhhRate()), "tag", item.getTag(), "externalNickname", item.getExternalNickname(),
                "totalBet", money(item.getTotalBet()), "profitLoss", money(item.getProfitLoss()),
                "autoProxy", bool(item.getAutoProxy()), "eatEnabled", bool(item.getEatEnabled()),
                "searchable", bool(item.getSearchable()), "fingerprint", value(item.getFingerprint(), ""),
                "privateChat", bool(item.getPrivateChat()), "webOnly", bool(item.getWebOnly()),
                "blueWhalePassword", value(item.getBlueWhalePassword(), ""), "avatar", value(item.getAvatar(), 1),
                "normalBet", money(basis[0]), "dragonBet", money(basis[1]),
                "normalRebate", money(normalPending.multiply(value(item.getNormalRate(), ZERO)).divide(new BigDecimal("100"))),
                "dragonRebate", money(dragonPending.multiply(value(item.getLhhRate(), ZERO)).divide(new BigDecimal("100"))));
        return result;
    }

    private Map<String, Object> amountRecordMap(AmountRecordDO item) {
        return map("id", item.getId(), "member", item.getMemberName(), "type", item.getType(), "amount", money(item.getAmount()),
                "status", item.getStatus(), "createdAt", date(item.getCreateTime()), "remark", value(item.getRemark(), ""),
                "auditedAt", item.getAuditedAt() == null ? null : date(item.getAuditedAt()));
    }

    private Map<String, Object> orderMap(OrderDO item, List<BetItemDO> bets) {
        return map("id", item.getId(), "member", item.getMemberName(), "period", item.getPeriod(), "content", item.getContent(),
                "amount", money(item.getAmount()), "win", money(item.getWin()), "status", item.getStatus(), "source", item.getSource(),
                "deliveryMode", item.getDeliveryMode(), "marketStatus", item.getMarketStatus(), "marketOrderId", item.getMarketOrderId(),
                "marketError", item.getMarketError(), "marketAttempts", value(item.getMarketAttempts(), 0),
                "createdAt", date(item.getCreateTime()), "settledAt", date(item.getSettledAt()),
                "itemCount", bets.size(), "items", bets.stream().map(this::betItemMap).toList());
    }

    private Map<String, Object> betItemMap(BetItemDO item) {
        return map("id", item.getId(), "play", item.getPlay(), "selection", item.getSelection(), "amount", money(item.getAmount()),
                "odds", money(item.getOdds()), "won", item.getWon(), "payout", money(item.getPayout()));
    }

    private Map<String, Object> drawMap(DrawDO item) {
        return map("period", item.getPeriod(), "result", item.getResult(), "bigSmall", item.getBigSmall(),
                "oddEven", item.getOddEven(), "dragonTiger", item.getDragonTiger(), "status", item.getStatus(),
                "settledAt", date(item.getSettledAt()));
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
        return map("id", item.getId(), "member", item.getMember(), "content", item.getContent(), "enabled", bool(item.getEnabled()),
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
        return map("id", item.getId(), "channel", item.getChannel(), "member", item.getMember(), "period", item.getPeriod(),
                "content", item.getContent(), "status", item.getStatus(), "orderId", item.getOrderId(), "error", item.getError(),
                "commandType", item.getCommandType(), "reply", item.getReply(), "processedAt", date(item.getProcessedAt()),
                "time", date(item.getCreateTime()));
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
        SystemStateDO state = requireState();
        state.setRoomOpen(open);
        systemStateMapper.updateById(state);
        log("-", Boolean.TRUE.equals(open) ? "开启房间" : "关闭房间");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(LotteryReqVO.Config reqVO) {
        LotteryConfigDO config = requireConfig();
        config.setUpstreamUrl(value(reqVO.getUrl(), ""));
        config.setUpstreamAccount(value(reqVO.getAccount(), ""));
        if (StrUtil.isNotBlank(reqVO.getPassword())) {
            config.setMarketPasswordEncrypted("externalized");
        }
        config.setAlertValue(value(reqVO.getAlertValue(), ZERO));
        config.setBossMode(reqVO.getBossMode());
        config.setPlayType(reqVO.getPlayType());
        config.setUseProxy(reqVO.getUseProxy());
        lotteryConfigMapper.updateById(config);
        log("-", "保存配置管理");
    }

    @Override
    public Map<String, Object> testConfig(LotteryReqVO.Config reqVO) {
        return map("status", "配置已保存，外部盘口写接口待接通", "connected", false);
    }

    @Override
    public Map<String, Object> syncMarket() {
        MarketConnectionDO market = findUserMarket(SecurityFrameworkUtils.getLoginUserId());
        return marketMap(market);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLinks(LotteryReqVO.LinkConfig reqVO) {
        LinkConfigDO links = first(linkConfigMapper.selectList(null));
        if (links == null) {
            links = new LinkConfigDO();
            links.setShortUrlMode(reqVO.getShortUrlMode());
            linkConfigMapper.insert(links);
        } else {
            links.setShortUrlMode(reqVO.getShortUrlMode());
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
        IntegrationDO item = integrationMapper.selectOne(new LambdaQueryWrapper<IntegrationDO>()
                .eq(IntegrationDO::getIntegrationKey, key));
        if (item == null) {
            item = new IntegrationDO();
            item.setIntegrationKey(key);
            item.setName(key);
            item.setStatus("已绑定");
            item.setAccount(value(reqVO.getAccount(), ""));
            item.setGroupName(value(reqVO.getGroup(), ""));
            integrationMapper.insert(item);
        } else {
            item.setAccount(value(reqVO.getAccount(), ""));
            item.setGroupName(value(reqVO.getGroup(), ""));
            item.setStatus("已绑定");
            integrationMapper.updateById(item);
        }
        log("-", "绑定第三方接入 " + key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindIntegration(String key) {
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
        if (create) {
            item = new MemberDO();
            item.setId(id());
            item.setOpenId(IdUtil.fastSimpleUUID());
            item.setAvatar(1);
            item.setVersion(0);
        }
        item.setName(reqVO.getName());
        item.setBalance(value(reqVO.getBalance(), ZERO));
        item.setStatus(value(reqVO.getStatus(), "离线"));
        item.setPartner(value(reqVO.getPartner(), "无"));
        item.setNormalRate(value(reqVO.getNormalRate(), ZERO));
        item.setLhhRate(value(reqVO.getLhhRate(), ZERO));
        item.setTag(value(reqVO.getTag(), "普通"));
        item.setExternalNickname(value(reqVO.getExternalNickname(), ""));
        item.setTotalBet(value(reqVO.getTotalBet(), value(item.getTotalBet(), ZERO)));
        item.setProfitLoss(value(reqVO.getProfitLoss(), value(item.getProfitLoss(), ZERO)));
        item.setAutoProxy(value(reqVO.getAutoProxy(), false));
        item.setEatEnabled(value(reqVO.getEatEnabled(), false));
        item.setSearchable(value(reqVO.getSearchable(), true));
        item.setFingerprint(value(reqVO.getFingerprint(), value(item.getFingerprint(), "")));
        item.setPrivateChat(value(reqVO.getPrivateChat(), false));
        item.setWebOnly(value(reqVO.getWebOnly(), false));
        item.setBlueWhalePassword(value(reqVO.getBlueWhalePassword(), ""));
        if (create) memberMapper.insert(item); else memberMapper.updateById(item);
        log(item.getName(), create ? "新增会员" : "修改会员");
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferMember(String id, LotteryReqVO.Transfer reqVO) {
        MemberDO member = requireMember(id);
        BigDecimal balance = value(member.getBalance(), ZERO);
        if ("下分".equals(reqVO.getType())) {
            if (balance.compareTo(reqVO.getAmount()) < 0) throw exception(MEMBER_BALANCE_NOT_ENOUGH);
            balance = balance.subtract(reqVO.getAmount());
        } else if ("上分".equals(reqVO.getType())) {
            balance = balance.add(reqVO.getAmount());
        } else {
            throw exception(RECORD_NOT_FOUND);
        }
        member.setBalance(money(balance));
        member.setVersion(value(member.getVersion(), 0) + 1);
        memberMapper.updateById(member);
        AmountRecordDO record = new AmountRecordDO();
        record.setId(id());
        record.setMemberId(member.getId());
        record.setMemberName(member.getName());
        record.setType(reqVO.getType());
        record.setAmount(reqVO.getAmount());
        record.setStatus("已通过");
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
        AmountRecordDO record = new AmountRecordDO();
        record.setId(id());
        record.setMemberId(member.getId());
        record.setMemberName(member.getName());
        record.setType(reqVO.getType());
        record.setAmount(reqVO.getAmount());
        record.setStatus("待审核");
        record.setRemark(value(reqVO.getRemark(), ""));
        record.setUserId(member.getUserId());
        amountRecordMapper.insert(record);
        log(member.getUserId(), member.getName(), "提交" + reqVO.getType() + "申请 " + reqVO.getAmount());
        return record.getId();
    }

    @Override
    public Map<String, Object> getMemberDetails(String id) {
        MemberDO member = requireMember(id);
        List<AmountRecordDO> amounts = amountRecordMapper.selectList(new LambdaQueryWrapper<AmountRecordDO>()
                .eq(AmountRecordDO::getMemberId, id).orderByDesc(AmountRecordDO::getCreateTime).last("LIMIT 20"));
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getMemberId, id).orderByDesc(OrderDO::getCreateTime).last("LIMIT 20"));
        List<String> ids = orders.stream().map(OrderDO::getId).toList();
        Map<String, List<BetItemDO>> items = ids.isEmpty() ? Map.of() : betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, ids)).stream()
                .collect(Collectors.groupingBy(BetItemDO::getOrderId));
        Map<String, Object> result = memberMap(member, Map.of(), Map.of());
        result.put("amountRecords", amounts.stream().map(this::amountRecordMap).toList());
        result.put("orders", orders.stream().map(item -> orderMap(item, items.getOrDefault(item.getId(), List.of()))).toList());
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
            member.setNormalRate(item.getNormalRate());
            member.setLhhRate(item.getLhhRate());
            memberMapper.updateById(member);
        });
        log("-", "批量保存会员返水比例");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRebates() {
        List<OrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .in(OrderDO::getStatus, "已中奖", "未中奖"));
        List<String> orderIds = orders.stream().map(OrderDO::getId).toList();
        List<BetItemDO> items = orderIds.isEmpty() ? List.of() : betItemMapper.selectList(
                new LambdaQueryWrapper<BetItemDO>().in(BetItemDO::getOrderId, orderIds));
        Map<String, String> orderMembers = orders.stream().collect(Collectors.toMap(OrderDO::getId, OrderDO::getMemberId));
        Map<String, BigDecimal[]> bases = new HashMap<>();
        for (BetItemDO item : items) {
            String memberId = orderMembers.get(item.getOrderId());
            if (memberId == null) continue;
            BigDecimal[] values = bases.computeIfAbsent(memberId, ignored -> new BigDecimal[]{ZERO, ZERO});
            int index = "龙虎".equals(item.getPlay()) ? 1 : 0;
            values[index] = values[index].add(value(item.getAmount(), ZERO));
        }
        Map<String, BigDecimal[]> used = calculateRebateUsage(rebateRecordMapper.selectList(null));
        BigDecimal total = ZERO;
        int count = 0;
        for (MemberDO member : memberMapper.selectList(null)) {
            BigDecimal[] base = bases.getOrDefault(member.getId(), new BigDecimal[]{ZERO, ZERO});
            BigDecimal[] consumed = used.getOrDefault(member.getId(), new BigDecimal[]{ZERO, ZERO});
            BigDecimal normalBet = base[0].subtract(consumed[0]).max(ZERO);
            BigDecimal dragonBet = base[1].subtract(consumed[1]).max(ZERO);
            BigDecimal normalAmount = money(normalBet.multiply(value(member.getNormalRate(), ZERO))
                    .divide(new BigDecimal("100")));
            BigDecimal dragonAmount = money(dragonBet.multiply(value(member.getLhhRate(), ZERO))
                    .divide(new BigDecimal("100")));
            BigDecimal amount = normalAmount.add(dragonAmount);
            if (amount.signum() <= 0) continue;
            RebateRecordDO record = new RebateRecordDO();
            record.setId(id());
            record.setMemberId(member.getId());
            record.setNormalBet(normalBet);
            record.setDragonBet(dragonBet);
            record.setNormalAmount(normalAmount);
            record.setDragonAmount(dragonAmount);
            record.setTotalAmount(amount);
            record.setUserId(member.getUserId());
            rebateRecordMapper.insert(record);
            member.setBalance(money(value(member.getBalance(), ZERO).add(amount)));
            member.setVersion(value(member.getVersion(), 0) + 1);
            memberMapper.updateById(member);
            total = total.add(amount);
            count++;
        }
        log("-", "发放返水 " + count + " 人，合计 " + money(total));
        return map("count", count, "amount", money(total));
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
        MemberDO member = requireMember(record.getMemberId());
        if ("已通过".equals(reqVO.getStatus())) {
            BigDecimal balance = value(member.getBalance(), ZERO);
            if ("下分".equals(record.getType())) {
                if (balance.compareTo(record.getAmount()) < 0) throw exception(MEMBER_BALANCE_NOT_ENOUGH);
                balance = balance.subtract(record.getAmount());
            } else {
                balance = balance.add(record.getAmount());
            }
            member.setBalance(money(balance));
            member.setVersion(value(member.getVersion(), 0) + 1);
            memberMapper.updateById(member);
        }
        record.setStatus(reqVO.getStatus());
        record.setRemark(StrUtil.blankToDefault(reqVO.getRemark(), value(record.getRemark(), "")));
        record.setAuditedAt(LocalDateTime.now());
        record.setAuditedBy(loginName());
        amountRecordMapper.updateById(record);
        log(member.getName(), record.getType() + reqVO.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> placeBet(LotteryReqVO.PlaceBet reqVO) {
        return betResultMap(placeBetInternal(reqVO, loginName()));
    }

    private BetResult placeBetInternal(LotteryReqVO.PlaceBet reqVO, String actor) {
        MemberDO member = StrUtil.isNotBlank(reqVO.getMemberId()) ? requireMember(reqVO.getMemberId())
                : findMemberByName(reqVO.getMemberName());
        if (member == null) throw exception(MEMBER_NOT_FOUND);
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
        String channel = StrUtil.blankToDefault(reqVO.getChannel(), "网页群");
        if ("网页群".equals(channel) && !enabled("pullEnable", member.getUserId())) throw exception(ROOM_CLOSED);

        LotteryConfigDO config = requireConfig(member.getUserId());
        if (!bool(config.getBossMode())) throw exception(MARKET_ORDER_UNAVAILABLE);
        if (bool(member.getWebOnly()) && !"网页群".equals(channel)) throw exception(ROOM_CLOSED);
        if ("私聊".equals(channel) && (!enabled("privateMode", member.getUserId()) || !bool(member.getPrivateChat()))) {
            throw exception(PRIVATE_BET_DISABLED);
        }
        String integrationKey = Map.of("微信", "wechat", "飞鱼", "fish", "蓝鲸", "blueWhale").get(channel);
        if (integrationKey != null) {
            IntegrationDO integration = DataPermissionUtils.executeIgnore(() -> integrationMapper.selectOne(
                    new LambdaQueryWrapper<IntegrationDO>().eq(IntegrationDO::getUserId, member.getUserId())
                            .eq(IntegrationDO::getIntegrationKey, integrationKey)));
            if (integration == null || !Set.of("已登录", "已绑定").contains(integration.getStatus())) throw exception(ROOM_CLOSED);
        }

        DrawDO existingDraw = DataPermissionUtils.executeIgnore(() -> drawMapper.selectOne(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, member.getUserId()).eq(DrawDO::getPeriod, reqVO.getPeriod())));
        if (existingDraw != null) throw exception(PERIOD_ALREADY_SETTLED);
        IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, member.getUserId()).eq(IssueDO::getPeriod, reqVO.getPeriod())));
        if (issue == null || !"OPEN".equals(issue.getStatus())) throw exception(ISSUE_NOT_OPEN);
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
        BigDecimal balance = money(value(member.getBalance(), ZERO).subtract(total));
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
        order.setSource(channel);
        order.setDeliveryMode("LOCAL_ONLY");
        order.setMarketStatus("NOT_REQUIRED");
        order.setMarketOrderId("");
        order.setMarketError("");
        order.setMarketAttempts(0);
        order.setPeriodSequence(periodSequence);
        order.setVersion(0);
        order.setUserId(member.getUserId());
        orderMapper.insert(order);
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
        message.setChannel(order.getSource());
        message.setMember(member.getName());
        message.setPeriod(order.getPeriod());
        message.setContent(order.getContent());
        message.setStatus("已下单");
        message.setOrderId(order.getId());
        message.setExternalId(reqVO.getExternalId());
        message.setError("");
        message.setCommandType("BET");
        message.setReply("下注成功，订单号 " + order.getId());
        message.setProcessedAt(LocalDateTime.now());
        message.setUserId(member.getUserId());
        messageMapper.insert(message);
        logAs(member.getUserId(), actor, member.getName(), "下注 " + total + "，期号 " + order.getPeriod());
        return new BetResult(message.getId(), order.getId(), member.getName(), order.getPeriod(), total, balance,
                parsed.size(), periodSequence, parsed, order.getStatus(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelOrder(String id) {
        return cancelResultMap(cancelOrderInternal(id, null, loginName()));
    }

    private CancelResult cancelOrderInternal(String id, String expectedMemberId, String actor) {
        OrderDO order = orderMapper.selectById(id);
        if (order == null || expectedMemberId != null && !expectedMemberId.equals(order.getMemberId())) {
            throw exception(ORDER_NOT_FOUND);
        }
        if (!"未开奖".equals(order.getStatus())) throw exception(ORDER_CAN_NOT_CANCEL);
        if ("MARKET_ADAPTER".equals(order.getDeliveryMode())
                && Set.of("SUBMITTED", "CONFIRMED").contains(order.getMarketStatus())) throw exception(ORDER_CAN_NOT_CANCEL);
        MemberDO member = requireMember(order.getMemberId());
        if (!enabled("openCancel", member.getUserId())) throw exception(ORDER_CAN_NOT_CANCEL);
        order.setStatus("已退码");
        order.setCancelledAt(LocalDateTime.now());
        int orderVersion = value(order.getVersion(), 0);
        order.setVersion(orderVersion + 1);
        int cancelled = orderMapper.update(order, new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, id)
                .eq(OrderDO::getUserId, order.getUserId()).eq(OrderDO::getStatus, "未开奖")
                .eq(OrderDO::getVersion, orderVersion));
        if (cancelled != 1) throw exception(ORDER_CAN_NOT_CANCEL);
        int memberVersion = value(member.getVersion(), 0);
        member.setBalance(money(value(member.getBalance(), ZERO).add(order.getAmount())));
        member.setTotalBet(money(value(member.getTotalBet(), ZERO).subtract(order.getAmount()).max(ZERO)));
        member.setVersion(memberVersion + 1);
        int refunded = memberMapper.update(member, new LambdaUpdateWrapper<MemberDO>()
                .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, member.getUserId())
                .eq(MemberDO::getVersion, memberVersion));
        if (refunded != 1) throw exception(BET_STATE_CHANGED);
        messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                .eq(MessageDO::getOrderId, id).set(MessageDO::getStatus, "已退码"));
        logAs(member.getUserId(), actor, member.getName(), "退码订单 " + id);
        return new CancelResult(id, "已退码", money(order.getAmount()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settlePeriod(String period, LotteryReqVO.Settle reqVO) {
        Long userId = Objects.requireNonNullElse(SecurityFrameworkUtils.getLoginUserId(), DEFAULT_OWNER_USER_ID);
        if (reqVO == null || StrUtil.isBlank(reqVO.getResult())) throw exception(BET_CONTENT_INVALID);
        LotteryBettingService.DrawResult draw = bettingService.deriveDraw(reqVO.getResult());
        DrawDO oldDraw = DataPermissionUtils.executeIgnore(() -> drawMapper.selectOne(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, userId).eq(DrawDO::getPeriod, period)));
        if (oldDraw != null && !Objects.equals(oldDraw.getResult(), draw.result())) throw exception(PERIOD_ALREADY_SETTLED);
        List<OrderDO> orders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getUserId, userId).eq(OrderDO::getPeriod, period).eq(OrderDO::getStatus, "未开奖")
                .orderByAsc(OrderDO::getCreateTime)));
        if (oldDraw != null && orders.isEmpty()) {
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
                            .add(item.getSelection() + "，套数" + money(item.getAmount()) + "，房费" + item.getPayout());
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
            MemberDO member = requireMember(order.getMemberId());
            int memberVersion = value(member.getVersion(), 0);
            member.setBalance(money(value(member.getBalance(), ZERO).add(payout)));
            member.setProfitLoss(money(value(member.getProfitLoss(), ZERO).add(payout.subtract(order.getAmount()))));
            member.setVersion(memberVersion + 1);
            int memberUpdated = memberMapper.update(member, new LambdaUpdateWrapper<MemberDO>()
                    .eq(MemberDO::getId, member.getId()).eq(MemberDO::getUserId, userId)
                    .eq(MemberDO::getVersion, memberVersion));
            if (memberUpdated != 1) throw exception(BET_STATE_CHANGED);
            messageMapper.update(null, new LambdaUpdateWrapper<MessageDO>().eq(MessageDO::getUserId, userId)
                    .eq(MessageDO::getOrderId, order.getId()).set(MessageDO::getStatus, order.getStatus()));
            totalAmount = totalAmount.add(order.getAmount());
            totalPayout = totalPayout.add(payout);
            details.add(map("orderId", order.getId(), "member", order.getMemberName(), "amount", money(order.getAmount()),
                    "payout", payout, "status", order.getStatus()));
        }
        DrawDO record = oldDraw;
        if (record == null) {
            record = new DrawDO();
            record.setUserId(userId);
            record.setPeriod(period);
            record.setResult(draw.result());
            record.setBigSmall(StrUtil.blankToDefault(reqVO.getBigSmall(), draw.bigSmall()));
            record.setOddEven(StrUtil.blankToDefault(reqVO.getOddEven(), draw.oddEven()));
            record.setDragonTiger(StrUtil.blankToDefault(reqVO.getDragonTiger(), draw.dragonTiger()));
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
            issue.setResult(draw.result());
            issue.setSource("手动");
            issue.setError("");
            issue.setOrderSequence(0);
            issueMapper.insert(issue);
        }
        if (!"SETTLED".equals(issue.getStatus())) {
            transitionIssue(issue, "SETTLED", "手动结算");
            issue.setResult(draw.result());
            issue.setSettledAt(LocalDateTime.now());
            issueMapper.updateById(issue);
        }
        for (Map.Entry<String, List<String>> entry : winningLines.entrySet()) {
            MemberDO member = requireMember(winningMemberIds.get(entry.getKey()));
            BigDecimal payout = money(winningPayouts.get(entry.getKey()));
            BigDecimal afterBalance = money(member.getBalance());
            MessageDO message = new MessageDO();
            message.setChannel("网页群");
            message.setMember(entry.getKey());
            message.setPeriod(period);
            message.setContent("");
            message.setStatus("已结算");
            message.setCommandType("SETTLEMENT");
            message.setReply("【" + entry.getKey() + "】入住：\n" + String.join("\n", entry.getValue())
                    + "\n合房费：" + payout + "\n【面积】" + money(afterBalance.subtract(payout))
                    + "\n【中介费】" + afterBalance + "\n\n--------\n【本次总派送】：" + money(totalPayout));
            message.setProcessedAt(LocalDateTime.now());
            message.setUserId(userId);
            messageMapper.insert(message);
        }
        logAs(userId, loginName(), "-", "结算期号 " + period + "，订单 " + orders.size() + " 笔，投额 "
                + money(totalAmount) + "，派彩 " + money(totalPayout));
        Map<String, Object> result = map("period", period, "result", record.getResult(), "bigSmall", record.getBigSmall(),
                "oddEven", record.getOddEven(), "dragonTiger", record.getDragonTiger(), "orders", details.size(),
                "totalBet", money(totalAmount), "totalPayout", money(totalPayout), "details", details,
                "alreadySettled", false);
        if (enabled("autoDiscount", userId)) result.put("rebate", applyRebates());
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
        SystemStateDO state = requireState();
        state.setRoomOpen("OPEN".equals(target));
        systemStateMapper.updateById(state);
        log("-", ("OPEN".equals(target) ? "开盘 " : "封盘 ") + period);
        if ("OPEN".equals(target)) runAutoProxy(period, issue.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settlePendingIssues() {
        return issueMapper.selectList(new LambdaQueryWrapper<IssueDO>().in(IssueDO::getStatus, "DRAWN", "SETTLING")).size();
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
        if (Set.of("查", "余额").contains(content)) {
            List<OrderDO> activeOrders = DataPermissionUtils.executeIgnore(() -> orderMapper.selectList(
                    new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getUserId, member.getUserId())
                            .eq(OrderDO::getMemberId, member.getId()).eq(OrderDO::getStatus, "未开奖")
                            .orderByAsc(OrderDO::getCreateTime)));
            String current = activeOrders.isEmpty() ? "目前无房源" : activeOrders.stream()
                    .map(order -> "[" + periodSuffix(order.getPeriod()) + "-" + value(order.getPeriodSequence(), 0) + "]" + order.getContent())
                    .collect(Collectors.joining("\n"));
            String reply = "@" + member.getName() + "\n【目前房源】：\n" + current + "\n\n【您目前】：\n" + money(member.getBalance());
            MessageDO message = saveCommandMessage(member, reqVO, "BALANCE", reply);
            return map("messageId", message.getId(), "reply", reply, "commandType", "BALANCE");
        }
        java.util.regex.Matcher amount = java.util.regex.Pattern.compile("^(上|下)(?:分)?(\\d+(?:\\.\\d+)?)$").matcher(content);
        if (amount.matches()) {
            LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
            transfer.setType("上".equals(amount.group(1)) ? "上分" : "下分");
            transfer.setAmount(new BigDecimal(amount.group(2)));
            transfer.setRemark(StrUtil.blankToDefault(reqVO.getChannel(), "消息") + "消息申请");
            String recordId = createAmountRequest(member.getId(), transfer);
            String commandType = "上分".equals(transfer.getType()) ? "DEPOSIT_REQUEST" : "WITHDRAW_REQUEST";
            String reply = "@" + member.getName() + "\n请稍后";
            MessageDO message = saveCommandMessage(member, reqVO, commandType, reply);
            return map("messageId", message.getId(), "reply", reply, "recordId", recordId, "commandType", commandType);
        }
        java.util.regex.Matcher cancel = java.util.regex.Pattern.compile("^退(?:码)?\\s*([A-Za-z0-9_-]+)$").matcher(content);
        if (cancel.matches()) {
            String orderId = cancel.group(1);
            CancelResult result = cancelOrderInternal(orderId, member.getId(), actor);
            String reply = "退码成功：" + result.id() + "，退回 " + result.refunded();
            MessageDO message = saveCommandMessage(member, reqVO, "CANCEL", reply);
            message.setOrderId(orderId);
            messageMapper.updateById(message);
            return map("messageId", message.getId(), "orderId", orderId, "reply", reply, "commandType", "CANCEL",
                    "refunded", result.refunded());
        }
        String period = StrUtil.trim(reqVO.getPeriod());
        if (StrUtil.isBlank(period)) {
            IssueDO issue = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                    .eq(IssueDO::getUserId, member.getUserId()).eq(IssueDO::getStatus, "OPEN")
                    .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
            if (issue == null) throw exception(ISSUE_NOT_OPEN);
            period = issue.getPeriod();
        }
        LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
        bet.setMemberId(member.getId());
        bet.setPeriod(period);
        bet.setContent(reqVO.getContent());
        bet.setChannel(reqVO.getChannel());
        bet.setExternalId(reqVO.getExternalId());
        BetResult result = placeBetInternal(bet, actor);
        String reply = formatBetReceipt(result, content);
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
        PresetOrderDO item = StrUtil.isBlank(reqVO.getId()) ? null : presetOrderMapper.selectById(reqVO.getId());
        boolean create = item == null;
        if (create) {
            item = new PresetOrderDO();
            item.setId(id());
        }
        item.setMember(value(reqVO.getMember(), ""));
        item.setContent(reqVO.getContent());
        item.setEnabled(reqVO.getEnabled());
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
        return TenantUtils.execute(reqVO.getTenantId(), () -> getRoomSessionInternal(requireRoomMember(reqVO)));
    }

    private Map<String, Object> getRoomSessionInternal(MemberDO member) {
        LotteryConfigDO config = requireConfig(member.getUserId());
        SystemStateDO state = requireState(member.getUserId());
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
        List<MessageDO> messages = DataPermissionUtils.executeIgnore(() -> messageMapper.selectList(
                new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getUserId, member.getUserId())
                        .eq(MessageDO::getMember, member.getName()).orderByDesc(MessageDO::getCreateTime).last("LIMIT 60")));
        List<DrawDO> draws = DataPermissionUtils.executeIgnore(() -> drawMapper.selectList(new LambdaQueryWrapper<DrawDO>()
                .eq(DrawDO::getUserId, member.getUserId()).orderByDesc(DrawDO::getSettledAt).last("LIMIT 20")));
        IssueDO current = DataPermissionUtils.executeIgnore(() -> issueMapper.selectOne(new LambdaQueryWrapper<IssueDO>()
                .eq(IssueDO::getUserId, member.getUserId()).in(IssueDO::getStatus, "OPEN", "CLOSED", "PENDING")
                .orderByDesc(IssueDO::getPeriod).last("LIMIT 1")));
        List<IssueTransitionDO> transitions = current == null ? List.of() : DataPermissionUtils.executeIgnore(() ->
                issueTransitionMapper.selectList(new LambdaQueryWrapper<IssueTransitionDO>()
                        .eq(IssueTransitionDO::getUserId, member.getUserId()).eq(IssueTransitionDO::getPeriod, current.getPeriod())
                        .orderByDesc(IssueTransitionDO::getCreateTime).last("LIMIT 20")));
        Map<String, Boolean> switches = DataPermissionUtils.executeIgnore(() -> switchSettingMapper.selectList(
                new LambdaQueryWrapper<SwitchSettingDO>().eq(SwitchSettingDO::getUserId, member.getUserId())))
                .stream().collect(Collectors.toMap(SwitchSettingDO::getSettingKey,
                        item -> bool(item.getEnabled()), (a, b) -> b));
        List<QuickCommandDO> commands = getEffectiveQuickCommands(member.getUserId(), true);
        return map("member", map("id", member.getId(), "name", member.getName(), "balance", money(member.getBalance()),
                        "totalBet", money(member.getTotalBet()), "profitLoss", money(member.getProfitLoss()), "avatar", member.getAvatar()),
                "room", map("name", value(config.getRoomName(), "幸运5"), "announcement", value(config.getAnnouncement(), ""),
                        "open", bool(state.getRoomOpen()), "online", value(state.getOnline(), 0),
                        "bettingEnabled", switches.getOrDefault("pullEnable", false),
                        "cancelEnabled", switches.getOrDefault("openCancel", false),
                        "features", map("groupImage", switches.getOrDefault("groupImage", false),
                                "privateImage", switches.getOrDefault("privateImage", false),
                                "prizeCard", switches.getOrDefault("prizeCard", false),
                                "imageBold", switches.getOrDefault("imageBold", false),
                                "linkToCode", switches.getOrDefault("linkToCode", false))),
                "suggestedPeriod", current == null ? "" : current.getPeriod(),
                "issue", current == null ? map("currentPeriod", "", "status", "UNAVAILABLE", "remainingSeconds", 0, "nextPeriod", "")
                        : map("currentPeriod", current.getPeriod(), "status", current.getStatus(),
                                "remainingSeconds", value(current.getRemainingSeconds(), 0), "nextPeriod", value(current.getNextPeriod(), ""),
                                "serverTime", current.getServerTime()),
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
                "messages", messages.stream().sorted(Comparator.comparing(MessageDO::getCreateTime)).map(this::messageMap).toList(),
                "quickCommands", commands.stream().map(this::quickCommandMap).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> roomPlaceBet(LotteryRoomReqVO.Bet reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomMember(reqVO);
            LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
            bet.setMemberId(member.getId());
            bet.setPeriod(reqVO.getPeriod());
            bet.setContent(reqVO.getContent());
            bet.setChannel("网页群");
            bet.setExternalId(StrUtil.isBlank(reqVO.getExternalId()) ? null : "room:" + member.getId() + ":" + reqVO.getExternalId());
            return betResultMap(placeBetInternal(bet, member.getName()));
        });
    }

    @Override
    public Map<String, Object> previewRoomBet(LotteryRoomReqVO.Bet reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomMember(reqVO);
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
            MemberDO member = requireRoomMember(reqVO);
            LotteryReqVO.IncomingMessage message = new LotteryReqVO.IncomingMessage();
            message.setMemberId(member.getId());
            message.setMemberName(member.getName());
            message.setPeriod(reqVO.getPeriod());
            message.setContent(reqVO.getContent());
            message.setChannel("网页群");
            message.setExternalId(StrUtil.isBlank(reqVO.getExternalId()) ? null : "room:" + member.getId() + ":" + reqVO.getExternalId());
            return processIncomingMessageInternal(message, member.getName());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRoomAmountRequest(LotteryRoomReqVO.Amount reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomMember(reqVO);
            LotteryReqVO.Transfer transfer = new LotteryReqVO.Transfer();
            transfer.setType(reqVO.getType());
            transfer.setAmount(reqVO.getAmount());
            transfer.setRemark(reqVO.getRemark());
            return map("id", createAmountRequest(member.getId(), transfer));
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelRoomOrder(String orderId, LotteryRoomReqVO.Credential reqVO) {
        return TenantUtils.execute(reqVO.getTenantId(), () -> {
            MemberDO member = requireRoomMember(reqVO);
            return cancelResultMap(cancelOrderInternal(orderId, member.getId(), member.getName()));
        });
    }

    private void runAutoProxy(String period, Long userId) {
        List<PresetOrderDO> presets = DataPermissionUtils.executeIgnore(() -> presetOrderMapper.selectList(
                new LambdaQueryWrapper<PresetOrderDO>().eq(PresetOrderDO::getUserId, userId)
                        .eq(PresetOrderDO::getEnabled, true)));
        if (presets.isEmpty()) return;
        List<MemberDO> members = DataPermissionUtils.executeIgnore(() -> memberMapper.selectList(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getUserId, userId).eq(MemberDO::getAutoProxy, true)));
        for (MemberDO member : members) {
            String externalId = "auto-proxy:" + member.getId() + ":" + period;
            if (messageMapper.selectOne(new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getExternalId, externalId)) != null) continue;
            PresetOrderDO preset = presets.get(Math.floorMod(Objects.hash(member.getId(), period), presets.size()));
            LotteryReqVO.PlaceBet bet = new LotteryReqVO.PlaceBet();
            bet.setMemberId(member.getId());
            bet.setPeriod(period);
            bet.setContent(preset.getContent());
            bet.setChannel("跟");
            bet.setExternalId(externalId);
            try {
                placeBetInternal(bet, "自动托");
            } catch (RuntimeException ex) {
                MessageDO message = new MessageDO();
                message.setChannel("跟");
                message.setMember(member.getName());
                message.setPeriod(period);
                message.setContent(preset.getContent());
                message.setStatus("失败");
                message.setExternalId(externalId);
                message.setError(ex.getMessage());
                message.setCommandType("AUTO_PROXY");
                message.setReply("自动托下注失败");
                message.setProcessedAt(LocalDateTime.now());
                message.setUserId(member.getUserId());
                messageMapper.insert(message);
            }
        }
    }

    private MemberDO requireRoomMember(LotteryRoomReqVO.Credential reqVO) {
        MemberDO member = memberMapper.selectOne(new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getOpenId, reqVO.getOpenId()));
        if (member == null) throw exception(ROOM_CREDENTIAL_INVALID);
        if (enabled("enableFingerCheck", member.getUserId()) && StrUtil.isNotBlank(member.getFingerprint())
                && !Objects.equals(member.getFingerprint(), reqVO.getFp())) throw exception(ROOM_CREDENTIAL_INVALID);
        if (enabled("enableFingerCheck", member.getUserId()) && StrUtil.isBlank(member.getFingerprint()) && StrUtil.isNotBlank(reqVO.getFp())) {
            member.setFingerprint(reqVO.getFp());
            memberMapper.updateById(member);
        }
        return member;
    }

    private MessageDO saveCommandMessage(MemberDO member, LotteryReqVO.IncomingMessage reqVO, String type, String reply) {
        MessageDO message = new MessageDO();
        message.setChannel(reqVO.getChannel());
        message.setMember(member.getName());
        message.setPeriod(value(reqVO.getPeriod(), ""));
        message.setContent(reqVO.getContent());
        message.setStatus("已处理");
        message.setExternalId(reqVO.getExternalId());
        message.setError("");
        message.setCommandType(type);
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

    private String formatBetReceipt(BetResult result, String content) {
        return "@" + result.member() + "\n[挂牌时间]" + periodSuffix(result.period()) + "\n" + content
                + "\n【户型审核成功】√√\n【编号】：" + result.periodSequence()
                + "\n【套内】：" + result.itemCount() + "\n【套外】：" + result.amount()
                + "\n【面积】：" + result.balance() + "\n点击退码";
    }

    private String periodSuffix(String period) {
        if (period == null) return "";
        return period.length() <= 3 ? period : period.substring(period.length() - 3);
    }

    private Map<String, BigDecimal[]> calculateBetBases(List<OrderDO> orders, Map<String, List<BetItemDO>> itemsByOrder) {
        Map<String, BigDecimal[]> result = new HashMap<>();
        for (OrderDO order : orders) {
            if (!Set.of("已中奖", "未中奖").contains(order.getStatus())) continue;
            BigDecimal[] values = result.computeIfAbsent(order.getMemberId(), ignored -> new BigDecimal[]{ZERO, ZERO});
            for (BetItemDO item : itemsByOrder.getOrDefault(order.getId(), List.of())) {
                int index = "龙虎".equals(item.getPlay()) ? 1 : 0;
                values[index] = values[index].add(value(item.getAmount(), ZERO));
            }
        }
        return result;
    }

    private Map<String, BigDecimal[]> calculateRebateUsage(List<RebateRecordDO> records) {
        Map<String, BigDecimal[]> result = new HashMap<>();
        for (RebateRecordDO item : records) {
            BigDecimal[] values = result.computeIfAbsent(item.getMemberId(), ignored -> new BigDecimal[]{ZERO, ZERO});
            values[0] = values[0].add(value(item.getNormalBet(), ZERO));
            values[1] = values[1].add(value(item.getDragonBet(), ZERO));
        }
        return result;
    }

    private List<Map<String, Object>> calculatePeriodChima(List<OrderDO> orders, List<MemberDO> members, SystemStateDO state) {
        Set<String> eatMembers = members.stream().filter(item -> bool(item.getEatEnabled())).map(MemberDO::getId).collect(Collectors.toSet());
        Map<String, BigDecimal[]> values = new HashMap<>();
        for (OrderDO order : orders) {
            if (!eatMembers.contains(order.getMemberId()) || "已退码".equals(order.getStatus())) continue;
            if (state != null && state.getChimaClearedAt() != null && order.getCreateTime() != null
                    && order.getCreateTime().isBefore(state.getChimaClearedAt())) continue;
            BigDecimal[] totals = values.computeIfAbsent(order.getPeriod(), ignored -> new BigDecimal[]{ZERO, ZERO});
            totals[0] = totals[0].add(value(order.getAmount(), ZERO));
            totals[1] = totals[1].add(value(order.getWin(), ZERO));
        }
        return values.entrySet().stream().map(entry -> map("periods", entry.getKey(), "fakeAmount", money(entry.getValue()[0]),
                        "totalWin", money(entry.getValue()[1]), "net", money(entry.getValue()[0].subtract(entry.getValue()[1]))))
                .sorted((a, b) -> String.valueOf(b.get("periods")).compareTo(String.valueOf(a.get("periods")))).toList();
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
        String old = issue.getStatus();
        if (Objects.equals(old, target)) return;
        IssueTransitionDO transition = new IssueTransitionDO();
        transition.setPeriod(issue.getPeriod());
        transition.setFromStatus(value(old, ""));
        transition.setToStatus(target);
        transition.setSource(source);
        transition.setUserId(issue.getUserId());
        issueTransitionMapper.insert(transition);
        issue.setStatus(target);
    }

    private Map<String, Object> issueMap(IssueDO item) {
        return map("period", item.getPeriod(), "status", item.getStatus(), "marketStatus", item.getMarketStatus(),
                "remainingSeconds", value(item.getRemainingSeconds(), 0), "serverTime", date(item.getServerTime()),
                "nextPeriod", value(item.getNextPeriod(), ""), "result", value(item.getResult(), ""),
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
        String roomUrl = base + "/room?tenantId=" + tenantId + "&openId=" + member.getOpenId();
        LinkConfigDO links = DataPermissionUtils.executeIgnore(() -> linkConfigMapper.selectOne(
                new LambdaQueryWrapper<LinkConfigDO>().eq(LinkConfigDO::getUserId, member.getUserId()).last("LIMIT 1")));
        int shortUrlMode = links == null ? 2 : value(links.getShortUrlMode(), 2);
        String shortUrl = shortUrlMode == 0 ? "" : base + "/r/" + member.getOpenId() + "?tenantId=" + tenantId;
        return Map.of("roomUrl", roomUrl, "longUrl", roomUrl, "shortUrl", shortUrl,
                "qrText", roomUrl, "openId", member.getOpenId());
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
