package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.enums.CommonStatusEnum;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryOwnerInitializationServiceTest {

    @Mock private LotteryConfigMapper lotteryConfigMapper;
    @Mock private SystemStateMapper systemStateMapper;
    @Mock private MarketConnectionMapper marketConnectionMapper;
    @Mock private LinkConfigMapper linkConfigMapper;
    @Mock private ChimaConfigMapper chimaConfigMapper;
    @Mock private SwitchSettingMapper switchSettingMapper;
    @Mock private IntegrationMapper integrationMapper;
    @Mock private OddMapper oddMapper;
    @Mock private QuickCommandMapper quickCommandMapper;
    @Mock private OwnerInitializationMapper ownerInitializationMapper;
    @Mock private RoleService roleService;
    @Mock private PermissionService permissionService;

    @InjectMocks
    private LotteryOwnerInitializationService service;

    @Test
    void initializeCopiesSafeTemplatesAndKeepsPrivateFieldsEmpty() {
        Long tenantId = 1L;
        Long ownerId = 200L;
        RoleDO ownerRole = new RoleDO().setId(3L).setCode("crm_admin")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(roleService.getRoleList()).thenReturn(List.of(ownerRole));

        LotteryConfigDO configTemplate = new LotteryConfigDO().setRoomName("模板房间")
                .setCloseTime("23:55").setSettleDelay(8)
                .setMinDeposit(new BigDecimal("100")).setMaxDeposit(new BigDecimal("50000"))
                .setAnnouncement("公告").setServiceUrl("https://service.example")
                .setChatUrl("https://chat.example").setUpstreamUrl("https://secret.example")
                .setUpstreamAccount("secret-account").setMarketPasswordEncrypted("secret-password")
                .setAlertValue(new BigDecimal("1000")).setBossMode(false).setPlayType(0).setUseProxy(true);
        when(lotteryConfigMapper.selectOne(any())).thenReturn(null, configTemplate);
        when(systemStateMapper.selectOne(any())).thenReturn(null);
        when(marketConnectionMapper.selectOne(any())).thenReturn(null);
        when(linkConfigMapper.selectOne(any())).thenReturn(null);
        when(chimaConfigMapper.selectOne(any())).thenReturn(null).thenReturn(null);

        SwitchSettingDO switchTemplate = new SwitchSettingDO().setSettingKey("pullEnable")
                .setLabel("网页群").setEnabled(true);
        when(switchSettingMapper.selectList(any())).thenReturn(List.of(), List.of(switchTemplate));
        IntegrationDO integrationTemplate = new IntegrationDO().setIntegrationKey("wechat")
                .setName("微信").setAccount("secret-wechat").setStatus("已登录");
        when(integrationMapper.selectList(any())).thenReturn(List.of(), List.of(integrationTemplate));
        OddDO oddTemplate = new OddDO().setCode("regex1d").setPlay("一定位")
                .setItem("").setRate(new BigDecimal("9.5")).setStatus("启用");
        when(oddMapper.selectList(any())).thenReturn(List.of(), List.of(oddTemplate));
        QuickCommandDO commandTemplate = new QuickCommandDO().setId("QC01")
                .setLabel("快捷下注").setContent("千1各1").setSort(1).setEnabled(true);
        when(quickCommandMapper.selectList(any())).thenReturn(List.of(), List.of(commandTemplate));

        service.initializeCurrentTenant(tenantId, ownerId, "boss200");

        verify(permissionService).assignUserRole(ownerId, Set.of(3L));

        ArgumentCaptor<LotteryConfigDO> configCaptor = ArgumentCaptor.forClass(LotteryConfigDO.class);
        verify(lotteryConfigMapper).insert(configCaptor.capture());
        LotteryConfigDO config = configCaptor.getValue();
        assertThat(config.getUserId()).isEqualTo(ownerId);
        assertThat(config.getRoomName()).isEqualTo("模板房间");
        assertThat(config.getBossMode()).isTrue();
        assertThat(config.getUpstreamUrl()).isEmpty();
        assertThat(config.getUpstreamAccount()).isEmpty();
        assertThat(config.getMarketPasswordEncrypted()).isEmpty();

        ArgumentCaptor<SystemStateDO> stateCaptor = ArgumentCaptor.forClass(SystemStateDO.class);
        verify(systemStateMapper).insert(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getOperatorUsername()).isEqualTo("boss200");
        assertThat(stateCaptor.getValue().getRoomOpen()).isFalse();

        ArgumentCaptor<IntegrationDO> integrationCaptor = ArgumentCaptor.forClass(IntegrationDO.class);
        verify(integrationMapper).insert(integrationCaptor.capture());
        assertThat(integrationCaptor.getValue().getAccount()).isEmpty();
        assertThat(integrationCaptor.getValue().getStatus()).isEqualTo("未登录");

        ArgumentCaptor<OddDO> oddCaptor = ArgumentCaptor.forClass(OddDO.class);
        verify(oddMapper).insert(oddCaptor.capture());
        assertThat(oddCaptor.getValue().getUserId()).isEqualTo(ownerId);
        assertThat(oddCaptor.getValue().getRate()).isEqualByComparingTo("9.5");

        ArgumentCaptor<QuickCommandDO> commandCaptor = ArgumentCaptor.forClass(QuickCommandDO.class);
        verify(quickCommandMapper).insert(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getUserId()).isEqualTo(ownerId);
        assertThat(commandCaptor.getValue().getId()).isNotEqualTo("QC01").hasSize(36);
    }

    @Test
    void initializeDoesNotOverwriteExistingOwnerData() {
        Long ownerId = 201L;
        when(roleService.getRoleList()).thenReturn(List.of());
        when(lotteryConfigMapper.selectOne(any())).thenReturn(new LotteryConfigDO());
        when(systemStateMapper.selectOne(any())).thenReturn(new SystemStateDO());
        when(marketConnectionMapper.selectOne(any())).thenReturn(new MarketConnectionDO());
        when(linkConfigMapper.selectOne(any())).thenReturn(new LinkConfigDO());
        when(chimaConfigMapper.selectOne(any())).thenReturn(new ChimaConfigDO());
        when(switchSettingMapper.selectList(any())).thenReturn(List.of(new SwitchSettingDO()));
        when(integrationMapper.selectList(any())).thenReturn(List.of(new IntegrationDO()));
        when(oddMapper.selectList(any())).thenReturn(List.of(new OddDO()));
        when(quickCommandMapper.selectList(any())).thenReturn(List.of(new QuickCommandDO()));

        service.initializeCurrentTenant(1L, ownerId, "existing");

        verify(permissionService, never()).assignUserRole(any(), any());
        verify(lotteryConfigMapper, never()).insert(any(LotteryConfigDO.class));
        verify(systemStateMapper, never()).insert(any(SystemStateDO.class));
        verify(marketConnectionMapper, never()).insert(any(MarketConnectionDO.class));
        verify(linkConfigMapper, never()).insert(any(LinkConfigDO.class));
        verify(chimaConfigMapper, never()).insert(any(ChimaConfigDO.class));
        verify(switchSettingMapper, never()).insert(any(SwitchSettingDO.class));
        verify(integrationMapper, never()).insert(any(IntegrationDO.class));
        verify(oddMapper, never()).insert(any(OddDO.class));
        verify(quickCommandMapper, never()).insert(any(QuickCommandDO.class));
    }

    @Test
    void automaticInitializationSkipsAccountWhenAnyInitializationMarkerAlreadyExists() {
        when(ownerInitializationMapper.insertIfAbsent(1L, 202L, "AUTO", 202L)).thenReturn(0);

        boolean initialized = service.initializeAutomaticallyCurrentTenant(1L, 202L, "existing-owner");

        assertThat(initialized).isFalse();
        verify(lotteryConfigMapper, never()).selectOne(any());
        verify(lotteryConfigMapper, never()).insert(any(LotteryConfigDO.class));
    }

    @Test
    void manualInitializationCreatesMarkerAndPreventsLaterAutomaticInitialization() {
        Long ownerId = 203L;
        OwnerInitializationDO marker = new OwnerInitializationDO();
        marker.setUserId(ownerId);
        marker.setInitializationCount(1);
        when(ownerInitializationMapper.insertIfAbsent(1L, ownerId, "MANUAL", 1L)).thenReturn(1);
        when(ownerInitializationMapper.selectOne(any())).thenReturn(marker);
        when(ownerInitializationMapper.insertIfAbsent(1L, ownerId, "AUTO", ownerId)).thenReturn(0);
        when(roleService.getRoleList()).thenReturn(List.of());
        when(lotteryConfigMapper.selectOne(any())).thenReturn(new LotteryConfigDO());
        when(systemStateMapper.selectOne(any())).thenReturn(new SystemStateDO());
        when(marketConnectionMapper.selectOne(any())).thenReturn(new MarketConnectionDO());
        when(linkConfigMapper.selectOne(any())).thenReturn(new LinkConfigDO());
        when(chimaConfigMapper.selectOne(any())).thenReturn(new ChimaConfigDO());
        when(switchSettingMapper.selectList(any())).thenReturn(List.of());
        when(integrationMapper.selectList(any())).thenReturn(List.of());
        when(oddMapper.selectList(any())).thenReturn(List.of());
        when(quickCommandMapper.selectList(any())).thenReturn(List.of());

        LotteryOwnerInitializationService.InitializationResult result = service.initializeManuallyCurrentTenant(
                1L, ownerId, "manual-owner", 1L);
        boolean automatic = service.initializeAutomaticallyCurrentTenant(1L, ownerId, "manual-owner");

        assertThat(result.initializationCount()).isEqualTo(1);
        assertThat(result.source()).isEqualTo("MANUAL");
        assertThat(automatic).isFalse();
    }

    @Test
    void initializationBackfillsOnlyMissingSwitchKeys() {
        Long ownerId = 204L;
        when(roleService.getRoleList()).thenReturn(List.of());
        when(lotteryConfigMapper.selectOne(any())).thenReturn(new LotteryConfigDO());
        when(systemStateMapper.selectOne(any())).thenReturn(new SystemStateDO());
        when(marketConnectionMapper.selectOne(any())).thenReturn(new MarketConnectionDO());
        when(linkConfigMapper.selectOne(any())).thenReturn(new LinkConfigDO());
        when(chimaConfigMapper.selectOne(any())).thenReturn(new ChimaConfigDO());
        SwitchSettingDO existing = new SwitchSettingDO().setSettingKey("pullEnable").setLabel("网页群").setEnabled(true);
        SwitchSettingDO missing = new SwitchSettingDO().setSettingKey("openCancel").setLabel("开启退码").setEnabled(true);
        when(switchSettingMapper.selectList(any())).thenReturn(List.of(existing), List.of(existing, missing));
        when(integrationMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(oddMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(quickCommandMapper.selectList(any())).thenReturn(List.of(), List.of());

        service.initializeCurrentTenant(1L, ownerId, "partial-owner");

        ArgumentCaptor<SwitchSettingDO> captor = ArgumentCaptor.forClass(SwitchSettingDO.class);
        verify(switchSettingMapper).insert(captor.capture());
        assertThat(captor.getValue().getSettingKey()).isEqualTo("openCancel");
        assertThat(captor.getValue().getUserId()).isEqualTo(ownerId);
    }

}
