package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.common.enums.CommonStatusEnum;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.ChimaConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IntegrationDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LinkConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketConnectionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OddDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OwnerInitializationDO;
import com.hnz.luck5.module.lottery.dal.dataobject.QuickCommandDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SwitchSettingDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SystemStateDO;
import com.hnz.luck5.module.lottery.dal.mysql.ChimaConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IntegrationMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LinkConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MarketConnectionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OddMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OwnerInitializationMapper;
import com.hnz.luck5.module.lottery.dal.mysql.QuickCommandMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SwitchSettingMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import com.hnz.luck5.module.system.dal.dataobject.permission.RoleDO;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import com.hnz.luck5.module.system.service.permission.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.OWNER_INITIALIZATION_NOT_ALLOWED;

/**
 * 为一个新老板账号初始化独立的 Lucky5 盘口数据。
 *
 * <p>初始化只补缺失数据，绝不覆盖已存在的账号配置。赔率、快捷指令等公共模板优先复制
 * 当前租户超级管理员的数据；链接、盘口凭据、第三方登录信息等账号私有内容始终清空。</p>
 */
@Service
@RequiredArgsConstructor
public class LotteryOwnerInitializationService {

    private static final Long SUPER_ADMIN_USER_ID = 1L;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String SOURCE_AUTO = "AUTO";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final LocalDateTime DEFAULT_EXPIRE_AT = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

    private final LotteryConfigMapper lotteryConfigMapper;
    private final SystemStateMapper systemStateMapper;
    private final MarketConnectionMapper marketConnectionMapper;
    private final LinkConfigMapper linkConfigMapper;
    private final ChimaConfigMapper chimaConfigMapper;
    private final SwitchSettingMapper switchSettingMapper;
    private final IntegrationMapper integrationMapper;
    private final OddMapper oddMapper;
    private final QuickCommandMapper quickCommandMapper;
    private final OwnerInitializationMapper ownerInitializationMapper;
    private final RoleService roleService;
    private final PermissionService permissionService;

    @Transactional(rollbackFor = Exception.class)
    public void initialize(Long tenantId, Long userId, String username) {
        initializeAutomatically(tenantId, userId, username);
    }

    /**
     * 自动初始化只允许抢占一次。自动或手动初始化留下标记后，后续登录不会再次执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean initializeAutomatically(Long tenantId, Long userId, String username) {
        return TenantUtils.execute(tenantId, () -> DataPermissionUtils.executeIgnore(
                () -> initializeAutomaticallyCurrentTenant(tenantId, userId, username)));
    }

    boolean initializeAutomaticallyCurrentTenant(Long tenantId, Long userId, String username) {
        int inserted = ownerInitializationMapper.insertIfAbsent(tenantId, userId, SOURCE_AUTO, userId);
        if (inserted == 0) {
            return false;
        }
        initializeCurrentTenant(tenantId, userId, username);
        return true;
    }

    /**
     * 超级管理员手动初始化可重复执行，但仍然只补缺失数据，不覆盖已有数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public InitializationResult initializeManually(Long tenantId, Long userId, String username, Long operatorUserId) {
        return TenantUtils.execute(tenantId, () -> DataPermissionUtils.executeIgnore(
                () -> {
                    if (permissionService.hasAnyRoles(userId, "super_admin")) {
                        throw exception(OWNER_INITIALIZATION_NOT_ALLOWED);
                    }
                    return initializeManuallyCurrentTenant(tenantId, userId, username, operatorUserId);
                }));
    }

    InitializationResult initializeManuallyCurrentTenant(Long tenantId, Long userId, String username,
                                                           Long operatorUserId) {
        int inserted = ownerInitializationMapper.insertIfAbsent(tenantId, userId, SOURCE_MANUAL, operatorUserId);
        OwnerInitializationDO marker = ownerInitializationMapper.selectOne(
                new LambdaQueryWrapper<OwnerInitializationDO>().eq(OwnerInitializationDO::getUserId, userId)
                        .last("LIMIT 1 FOR UPDATE"));
        initializeCurrentTenant(tenantId, userId, username);
        if (inserted == 0) {
            marker.setLastSource(SOURCE_MANUAL);
            marker.setInitializationCount(value(marker.getInitializationCount(), 1) + 1);
            marker.setLastInitializedAt(LocalDateTime.now());
            marker.setLastOperatorUserId(operatorUserId);
            ownerInitializationMapper.updateById(marker);
        }
        int count = inserted == 1 ? 1 : marker.getInitializationCount();
        return new InitializationResult(userId, count, SOURCE_MANUAL);
    }

    void initializeCurrentTenant(Long tenantId, Long userId, String username) {
        initializeOwnerRole(userId);
        initializeConfig(userId);
        initializeState(userId, username);
        initializeMarketConnection(userId);
        initializeLinkConfig(userId);
        initializeChimaConfig(userId);
        initializeSwitches(userId);
        initializeIntegrations(userId);
        initializeOdds(userId);
        initializeQuickCommands(tenantId, userId);
    }

    private void initializeOwnerRole(Long userId) {
        List<RoleDO> roles = roleService.getRoleList();
        RoleDO ownerRole = roles.stream()
                .filter(role -> CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()))
                .filter(role -> "crm_admin".equals(role.getCode()))
                .findFirst()
                .orElseGet(() -> roles.stream()
                        .filter(role -> CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()))
                        .filter(role -> "tenant_admin".equals(role.getCode()))
                        .findFirst().orElse(null));
        if (ownerRole != null) {
            permissionService.assignUserRole(userId, Set.of(ownerRole.getId()));
        }
    }

    private void initializeConfig(Long userId) {
        if (findConfig(userId) != null) {
            return;
        }
        LotteryConfigDO template = findConfig(SUPER_ADMIN_USER_ID);
        LotteryConfigDO config = new LotteryConfigDO();
        config.setUserId(userId);
        config.setRoomName(value(template == null ? null : template.getRoomName(), "幸运5"));
        config.setCloseTime(value(template == null ? null : template.getCloseTime(), ""));
        config.setSettleDelay(value(template == null ? null : template.getSettleDelay(), 0));
        config.setMinDeposit(value(template == null ? null : template.getMinDeposit(), ZERO));
        config.setMaxDeposit(value(template == null ? null : template.getMaxDeposit(), ZERO));
        config.setAnnouncement(value(template == null ? null : template.getAnnouncement(), ""));
        config.setServiceUrl(value(template == null ? null : template.getServiceUrl(), ""));
        config.setChatUrl(value(template == null ? null : template.getChatUrl(), ""));
        config.setUpstreamUrl("");
        config.setUpstreamAccount("");
        config.setMarketPasswordEncrypted("");
        config.setAlertValue(value(template == null ? null : template.getAlertValue(), ZERO));
        config.setBossMode(true);
        config.setPlayType(value(template == null ? null : template.getPlayType(), 2));
        config.setUseProxy(value(template == null ? null : template.getUseProxy(), true));
        lotteryConfigMapper.insert(config);
    }

    private void initializeState(Long userId, String username) {
        SystemStateDO existing = systemStateMapper.selectOne(new LambdaQueryWrapper<SystemStateDO>()
                .eq(SystemStateDO::getUserId, userId).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        SystemStateDO state = new SystemStateDO().setOperatorUsername(value(username, ""))
                .setExpireAt(DEFAULT_EXPIRE_AT).setRoomOpen(false).setOnline(0);
        state.setUserId(userId);
        systemStateMapper.insert(state);
    }

    private void initializeMarketConnection(Long userId) {
        MarketConnectionDO existing = marketConnectionMapper.selectOne(new LambdaQueryWrapper<MarketConnectionDO>()
                .eq(MarketConnectionDO::getUserId, userId).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        MarketConnectionDO connection = new MarketConnectionDO().setStatus("未配置")
                .setLineUrl("").setDisplayAccount("").setError("");
        connection.setUserId(userId);
        marketConnectionMapper.insert(connection);
    }

    private void initializeLinkConfig(Long userId) {
        LinkConfigDO existing = linkConfigMapper.selectOne(new LambdaQueryWrapper<LinkConfigDO>()
                .eq(LinkConfigDO::getUserId, userId).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        LinkConfigDO linkConfig = new LinkConfigDO().setDeviceId("").setDealerUrl("")
                .setRoomUrl("").setShortUrl("").setQrMode("").setShortUrlMode(2);
        linkConfig.setUserId(userId);
        linkConfigMapper.insert(linkConfig);
    }

    private void initializeChimaConfig(Long userId) {
        ChimaConfigDO existing = chimaConfigMapper.selectOne(new LambdaQueryWrapper<ChimaConfigDO>()
                .eq(ChimaConfigDO::getUserId, userId).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        ChimaConfigDO template = chimaConfigMapper.selectOne(new LambdaQueryWrapper<ChimaConfigDO>()
                .eq(ChimaConfigDO::getUserId, SUPER_ADMIN_USER_ID).last("LIMIT 1"));
        ChimaConfigDO chimaConfig = new ChimaConfigDO()
                .setSiZiXian(chimaValue(template == null ? null : template.getSiZiXian()))
                .setSanZiXian(chimaValue(template == null ? null : template.getSanZiXian()))
                .setErZiXian(chimaValue(template == null ? null : template.getErZiXian()))
                .setDanZiXian(chimaValue(template == null ? null : template.getDanZiXian()))
                .setSiDingWei(chimaValue(template == null ? null : template.getSiDingWei()))
                .setSanDingWei(chimaValue(template == null ? null : template.getSanDingWei()))
                .setErDingWei(chimaValue(template == null ? null : template.getErDingWei()))
                .setYiDingWei(chimaValue(template == null ? null : template.getYiDingWei()))
                .setYinKuiMax(chimaValue(template == null ? null : template.getYinKuiMax()))
                .setYinKuiMin(chimaValue(template == null ? null : template.getYinKuiMin()));
        chimaConfig.setUserId(userId);
        chimaConfigMapper.insert(chimaConfig);
    }

    private void initializeSwitches(Long userId) {
        List<SwitchSettingDO> existing = findSwitches(userId);
        Set<String> existingKeys = existing.stream().map(SwitchSettingDO::getSettingKey).collect(Collectors.toSet());
        List<SwitchTemplate> templates = findSwitches(SUPER_ADMIN_USER_ID).stream()
                .map(item -> new SwitchTemplate(item.getSettingKey(), item.getLabel(), item.getEnabled()))
                .toList();
        if (templates.isEmpty()) {
            templates = defaultSwitches();
        }
        templates.stream().filter(template -> !existingKeys.contains(template.key())).forEach(template -> {
            SwitchSettingDO setting = new SwitchSettingDO().setSettingKey(template.key())
                    .setLabel(template.label()).setEnabled(Boolean.TRUE.equals(template.enabled()));
            setting.setUserId(userId);
            switchSettingMapper.insert(setting);
        });
    }

    private void initializeIntegrations(Long userId) {
        List<IntegrationDO> existing = findIntegrations(userId);
        Set<String> existingKeys = existing.stream().map(IntegrationDO::getIntegrationKey).collect(Collectors.toSet());
        List<IntegrationTemplate> templates = findIntegrations(SUPER_ADMIN_USER_ID).stream()
                .map(item -> new IntegrationTemplate(item.getIntegrationKey(), item.getName()))
                .toList();
        if (templates.isEmpty()) {
            templates = List.of(new IntegrationTemplate("blueWhale", "蓝鲸"),
                    new IntegrationTemplate("fish", "飞鱼"),
                    new IntegrationTemplate("wechat", "微信"));
        }
        templates.stream().filter(template -> !existingKeys.contains(template.key())).forEach(template -> {
            IntegrationDO integration = new IntegrationDO().setIntegrationKey(template.key())
                    .setName(template.name()).setAccount("").setGroupName("").setStatus("未登录");
            integration.setUserId(userId);
            integrationMapper.insert(integration);
        });
    }

    private void initializeOdds(Long userId) {
        Set<String> existingCodes = findOdds(userId).stream().map(OddDO::getCode).collect(Collectors.toSet());
        List<OddTemplate> templates = findOdds(SUPER_ADMIN_USER_ID).stream()
                .map(item -> new OddTemplate(item.getCode(), item.getPlay(), item.getItem(), item.getRate(),
                        item.getSecondaryRate(), item.getMinLimit(), item.getMaxLimit(), item.getStatus()))
                .toList();
        if (templates.isEmpty()) {
            templates = defaultOdds();
        }
        templates.stream().filter(template -> !existingCodes.contains(template.code())).forEach(template -> {
            OddDO odd = new OddDO().setCode(template.code()).setPlay(template.play())
                    .setItem(value(template.item(), "")).setRate(value(template.rate(), ZERO))
                    .setSecondaryRate(template.secondaryRate()).setMinLimit(template.minLimit())
                    .setMaxLimit(template.maxLimit()).setStatus(value(template.status(), "启用"));
            odd.setUserId(userId);
            oddMapper.insert(odd);
        });
    }

    private void initializeQuickCommands(Long tenantId, Long userId) {
        Set<String> existingCommands = findQuickCommands(userId).stream().map(this::commandKey)
                .collect(Collectors.toSet());
        List<QuickCommandTemplate> templates = findQuickCommands(SUPER_ADMIN_USER_ID).stream()
                .map(item -> new QuickCommandTemplate(item.getId(), item.getLabel(), item.getContent(),
                        item.getSort(), item.getEnabled()))
                .toList();
        if (templates.isEmpty()) {
            templates = defaultQuickCommands();
        }
        templates.stream().filter(template -> !existingCommands.contains(commandKey(template))).forEach(template -> {
            QuickCommandDO command = new QuickCommandDO()
                    .setId(stableCommandId(tenantId, userId, template.sourceId()))
                    .setLabel(template.label()).setContent(template.content())
                    .setSort(value(template.sort(), 0)).setEnabled(Boolean.TRUE.equals(template.enabled()));
            command.setUserId(userId);
            quickCommandMapper.insert(command);
        });
    }

    private LotteryConfigDO findConfig(Long userId) {
        return lotteryConfigMapper.selectOne(new LambdaQueryWrapper<LotteryConfigDO>()
                .eq(LotteryConfigDO::getUserId, userId).last("LIMIT 1"));
    }

    private List<SwitchSettingDO> findSwitches(Long userId) {
        return switchSettingMapper.selectList(new LambdaQueryWrapper<SwitchSettingDO>()
                .eq(SwitchSettingDO::getUserId, userId).orderByAsc(SwitchSettingDO::getId));
    }

    private List<IntegrationDO> findIntegrations(Long userId) {
        return integrationMapper.selectList(new LambdaQueryWrapper<IntegrationDO>()
                .eq(IntegrationDO::getUserId, userId).orderByAsc(IntegrationDO::getId));
    }

    private List<OddDO> findOdds(Long userId) {
        return oddMapper.selectList(new LambdaQueryWrapper<OddDO>()
                .eq(OddDO::getUserId, userId).orderByAsc(OddDO::getId));
    }

    private List<QuickCommandDO> findQuickCommands(Long userId) {
        return quickCommandMapper.selectList(new LambdaQueryWrapper<QuickCommandDO>()
                .eq(QuickCommandDO::getUserId, userId)
                .orderByAsc(QuickCommandDO::getSort).orderByAsc(QuickCommandDO::getId));
    }

    private String stableCommandId(Long tenantId, Long userId, String sourceId) {
        String seed = tenantId + ":" + userId + ":" + sourceId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String commandKey(QuickCommandDO command) {
        return value(command.getLabel(), "") + "\u0000" + value(command.getContent(), "");
    }

    private String commandKey(QuickCommandTemplate command) {
        return value(command.label(), "") + "\u0000" + value(command.content(), "");
    }

    private BigDecimal chimaValue(BigDecimal value) {
        return value(value, ZERO);
    }

    private <T> T value(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private List<SwitchTemplate> defaultSwitches() {
        return List.of(
                new SwitchTemplate("openCancel", "开启退码", true),
                new SwitchTemplate("groupImage", "群发图", false),
                new SwitchTemplate("privateImage", "私发图", false),
                new SwitchTemplate("privateMode", "开启私聊", false),
                new SwitchTemplate("pullEnable", "网页群", true),
                new SwitchTemplate("dailyClear", "每天自动清理流水", false),
                new SwitchTemplate("wangkaEnable", "网咔模式", false),
                new SwitchTemplate("delayOrder", "延迟跟单", false),
                new SwitchTemplate("enableFingerCheck", "校验指纹", false),
                new SwitchTemplate("syncEnable", "同步网盘", false),
                new SwitchTemplate("dragonTigerSeparateRebate", "龙虎分开反水", false),
                new SwitchTemplate("urlEncode", "网址加密", false),
                new SwitchTemplate("delayOpen", "延迟开", false),
                new SwitchTemplate("linkToCode", "拉发二维码", false),
                new SwitchTemplate("prizeCard", "刮刮卡", false),
                new SwitchTemplate("imageBold", "图加粗", false),
                new SwitchTemplate("autoDiscount", "关盘后自动反水", false));
    }

    private List<OddTemplate> defaultOdds() {
        return List.of(
                odd("regex4x", "四字现", "360"), odd("regex3x", "三字现", "45"),
                odd("regex2x", "二字现", "9"), odd("regex4d", "四定位", "9600"),
                odd("regex4d4", "四条", "7000"), odd("regex3d", "三定位", "960"),
                odd("regex2d", "二定位", "96"), odd("regex1d", "一定位", "9"),
                odd("regexlh", "龙虎", "0"), odd("regexh", "和", "0"));
    }

    private OddTemplate odd(String code, String play, String rate) {
        return new OddTemplate(code, play, "", new BigDecimal(rate), null, null, null, "启用");
    }

    private List<QuickCommandTemplate> defaultQuickCommands() {
        return List.of(
                quick("QC01", "11335566778899倒四定各0.5", 1),
                quick("QC02", "2456789百0245689个各20", 2),
                quick("QC03", "123456780头123467890百234567890十0123456789个。两数合012345除三重除三兄弟各0.5", 3),
                quick("QC04", "6789千13579百0123457十各5", 4),
                quick("QC05", "头13579百24680十1245789各0.5", 5),
                quick("QC06", "百13579十1245798尾02468各0.5", 6),
                quick("QC07", "023456789千023456789百012345679十012345679个。含016789千十合01234579千个合23456789百十合01245679除三重除两双重各0.5", 7),
                quick("QC08", "1243790头1234567百1234789尾各2", 8),
                quick("QC09", "0123456789千百十个。含347两数合024取两兄弟各0.5", 9),
                quick("QC10", "百02468十1245789尾13579各0.5", 10));
    }

    private QuickCommandTemplate quick(String id, String content, int sort) {
        return new QuickCommandTemplate(id, content, content, sort, true);
    }

    private record SwitchTemplate(String key, String label, Boolean enabled) {
    }

    private record IntegrationTemplate(String key, String name) {
    }

    private record OddTemplate(String code, String play, String item, BigDecimal rate,
                               BigDecimal secondaryRate, BigDecimal minLimit, BigDecimal maxLimit, String status) {
    }

    private record QuickCommandTemplate(String sourceId, String label, String content, Integer sort, Boolean enabled) {
    }

    public record InitializationResult(Long userId, int initializationCount, String source) {
    }

}
