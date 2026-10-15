package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.framework.tenant.core.context.TenantContextHolder;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.SystemStateDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OperationLogMapper;
import com.hnz.luck5.module.lottery.dal.mysql.SystemStateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LotteryBossRoomStartTest {

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "boss-room-config"),
                LotteryConfigDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "boss-room-issue"),
                IssueDO.class);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void rejectsMissingCredentialsBeforeOpeningRoom() {
        LotteryConfigDO config = new LotteryConfigDO();
        config.setUserId(1L);
        config.setBossMode(true);
        LotteryServiceImpl service = service(config, mock(LotteryMarketSyncService.class), mock(SystemStateMapper.class));

        assertThatThrownBy(() -> service.setRoom(true)).hasMessageContaining("请先配置会员网址");
    }

    @Test
    void rejectsFailedLiveLoginAndDoesNotOpenRoom() {
        LotteryConfigDO config = configuredBoss();
        LotteryMarketSyncService sync = mock(LotteryMarketSyncService.class);
        SystemStateMapper stateMapper = mock(SystemStateMapper.class);
        when(sync.verifyCurrentConnection(1L, 1L)).thenReturn(Map.of("status", "连接失败"));
        LotteryServiceImpl service = service(config, sync, stateMapper);

        assertThatThrownBy(() -> service.setRoom(true)).hasMessageContaining("确认登录成功");
        verify(stateMapper, never()).updateById(any(SystemStateDO.class));
        verify(sync).verifyCurrentConnection(1L, 1L);
    }

    @Test
    void opensOnlyAfterSuccessfulLiveLogin() {
        LotteryConfigDO config = configuredBoss();
        LotteryMarketSyncService sync = mock(LotteryMarketSyncService.class);
        SystemStateMapper stateMapper = mock(SystemStateMapper.class);
        SystemStateDO state = new SystemStateDO();
        state.setRoomOpen(false);
        when(sync.verifyCurrentConnection(1L, 1L)).thenReturn(Map.of("status", "已连接"));
        when(stateMapper.selectList(null)).thenReturn(List.of(state));
        LotteryServiceImpl service = service(config, sync, stateMapper);

        service.setRoom(true);

        verify(stateMapper).updateById(state);
        org.assertj.core.api.Assertions.assertThat(state.getRoomOpen()).isTrue();
    }

    @Test
    void openingRoomSchedulesAutoProxyForTheCurrentOpenIssue() {
        LotteryConfigDO config = configuredBoss();
        LotteryMarketSyncService sync = mock(LotteryMarketSyncService.class);
        SystemStateMapper stateMapper = mock(SystemStateMapper.class);
        IssueMapper issueMapper = mock(IssueMapper.class);
        LotteryIssueFreshnessPolicy freshness = mock(LotteryIssueFreshnessPolicy.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SystemStateDO state = new SystemStateDO();
        state.setUserId(1L);
        state.setRoomOpen(false);
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261015001");
        issue.setStatus("OPEN");
        issue.setOpenedAt(LocalDateTime.now());
        when(sync.verifyCurrentConnection(1L, 1L)).thenReturn(Map.of("status", "已连接"));
        when(stateMapper.selectList(null)).thenReturn(List.of(state));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        LotteryServiceImpl service = service(config, sync, stateMapper);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshness);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);

        service.setRoom(true);

        var event = org.mockito.ArgumentCaptor.forClass(LotteryRoomStartedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new LotteryRoomStartedEvent(1L, 1L, "20261015001"));
    }

    @Test
    void openingRoomDoesNotScheduleAnIssuePastItsBettingCutoff() {
        LotteryConfigDO config = configuredBoss();
        LotteryMarketSyncService sync = mock(LotteryMarketSyncService.class);
        SystemStateMapper stateMapper = mock(SystemStateMapper.class);
        IssueMapper issueMapper = mock(IssueMapper.class);
        LotteryIssueFreshnessPolicy freshness = mock(LotteryIssueFreshnessPolicy.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SystemStateDO state = new SystemStateDO();
        state.setUserId(1L);
        state.setRoomOpen(false);
        IssueDO issue = new IssueDO();
        issue.setPeriod("20261015001");
        issue.setStatus("OPEN");
        issue.setOpenedAt(LocalDateTime.now());
        when(sync.verifyCurrentConnection(1L, 1L)).thenReturn(Map.of("status", "已连接"));
        when(stateMapper.selectList(null)).thenReturn(List.of(state));
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(freshness.isBettingClosed(issue)).thenReturn(true);
        LotteryServiceImpl service = service(config, sync, stateMapper);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", freshness);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);

        service.setRoom(true);

        verify(publisher, never()).publishEvent(any(LotteryRoomStartedEvent.class));
    }

    private LotteryConfigDO configuredBoss() {
        LotteryConfigDO config = new LotteryConfigDO();
        config.setUserId(1L);
        config.setBossMode(true);
        config.setUpstreamUrl("https://example.invalid/");
        config.setUpstreamAccount("test-account");
        config.setMarketPasswordEncrypted("test-encrypted-password");
        return config;
    }

    private LotteryServiceImpl service(LotteryConfigDO config, LotteryMarketSyncService sync,
                                       SystemStateMapper stateMapper) {
        TenantContextHolder.setTenantId(1L);
        LotteryConfigMapper configMapper = mock(LotteryConfigMapper.class);
        when(configMapper.selectOne(any())).thenReturn(config);
        LotteryServiceImpl service = new LotteryServiceImpl();
        ReflectionTestUtils.setField(service, "lotteryConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "marketSyncService", sync);
        ReflectionTestUtils.setField(service, "systemStateMapper", stateMapper);
        ReflectionTestUtils.setField(service, "operationLogMapper", mock(OperationLogMapper.class));
        return service;
    }
}
