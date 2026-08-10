package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.AutoProxyExecutionDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.AutoProxyExecutionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryAutoProxyServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-auto-proxy-test"),
                AutoProxyExecutionDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-preset-test"),
                PresetOrderDO.class);
    }

    @Mock private PresetOrderMapper presetOrderMapper;
    @Mock private MemberMapper memberMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AutoProxyExecutionMapper executionMapper;
    @Mock private LotteryService lotteryService;

    @InjectMocks
    private LotteryAutoProxyService service;

    @Test
    void openingPeriodOnlyCreatesOneDurableDelayedTask() {
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.insert(any(AutoProxyExecutionDO.class))).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        LotteryAutoProxyService.RunResult result = service.run(10L, "20260809001");

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.scheduled()).isEqualTo(1);
        ArgumentCaptor<AutoProxyExecutionDO> captor = ArgumentCaptor.forClass(AutoProxyExecutionDO.class);
        verify(executionMapper).insert(captor.capture());
        AutoProxyExecutionDO task = captor.getValue();
        assertThat(task.getMemberId()).isEqualTo("M-1");
        assertThat(task.getStatus()).isEqualTo("SCHEDULED");
        assertThat(task.getScheduledAt()).isBetween(before.plusSeconds(4), LocalDateTime.now().plusSeconds(31));
        verify(lotteryService, never()).placeAutoBet(anyLong(), any(), anyString());
    }

    @Test
    void dueTaskPlacesLocalVirtualOrderWithoutTopUpWhenBalanceIsEnough() {
        mockDueTask(new BigDecimal("100"), new BigDecimal("10"));
        when(lotteryService.placeAutoBet(anyLong(), any(), anyString())).thenReturn(Map.of("orderId", "O-1"));

        service.execute("T-1", 10L);

        verify(lotteryService, never()).autoTopUpProxy(anyLong(), anyString(), anyString(), any());
        ArgumentCaptor<LotteryReqVO.PlaceBet> betCaptor = ArgumentCaptor.forClass(LotteryReqVO.PlaceBet.class);
        verify(lotteryService).placeAutoBet(anyLong(), betCaptor.capture(), anyString());
        assertThat(betCaptor.getValue().getMemberId()).isEqualTo("M-1");
        assertThat(betCaptor.getValue().getPeriod()).isEqualTo("20260809001");
        assertThat(betCaptor.getValue().getExternalId()).startsWith("auto-proxy:")
                .endsWith(":20260809001");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PresetOrderDO>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(presetOrderMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment().toLowerCase()).doesNotContain("enabled");
        verify(executionMapper, times(2)).update(any(), any());
    }

    @Test
    void dueTaskRandomlyChoosesExactlyOneUsablePreset() {
        mockDueTask();
        PresetOrderDO first = preset("P-1", "大10");
        PresetOrderDO second = preset("P-2", "小10");
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(first, second));
        when(lotteryService.prepareAutoProxyBet(anyLong(), anyString(), anyString()))
                .thenReturn(Map.of("balance", new BigDecimal("100"), "requiredAmount", new BigDecimal("10")));
        when(lotteryService.placeAutoBet(anyLong(), any(), anyString())).thenReturn(Map.of("orderId", "O-1"));

        service.execute("T-1", 10L);

        ArgumentCaptor<LotteryReqVO.PlaceBet> betCaptor = ArgumentCaptor.forClass(LotteryReqVO.PlaceBet.class);
        verify(lotteryService, times(1)).placeAutoBet(anyLong(), betCaptor.capture(), anyString());
        assertThat(betCaptor.getValue().getContent()).isIn(Set.of("大10", "小10"));
    }

    @Test
    void insufficientBalanceAutoApprovesConfiguredTopUpBeforeBetting() {
        mockDueTask(BigDecimal.ZERO, new BigDecimal("25"));
        when(lotteryService.autoTopUpProxy(10L, "M-1", "20260809001", new BigDecimal("1000.00")))
                .thenReturn(Map.of("duplicate", false));
        when(lotteryService.placeAutoBet(anyLong(), any(), anyString())).thenReturn(Map.of("orderId", "O-1"));

        service.execute("T-1", 10L);

        verify(lotteryService).autoTopUpProxy(10L, "M-1", "20260809001", new BigDecimal("1000.00"));
        verify(lotteryService).placeAutoBet(anyLong(), any(), anyString());
        verify(executionMapper, times(2)).update(any(), any());
    }

    @Test
    void openingPeriodSkipsBotWhenCompletedOrderAlreadyExists() {
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        MessageDO completed = new MessageDO();
        completed.setOrderId("O-1");
        when(messageMapper.selectOne(any())).thenReturn(completed);

        LotteryAutoProxyService.RunResult result = service.run(10L, "20260809001");

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.scheduled()).isZero();
        verify(executionMapper, never()).insert(any(AutoProxyExecutionDO.class));
        verify(lotteryService, never()).placeAutoBet(anyLong(), any(), anyString());
    }

    private void mockDueTask(BigDecimal balance, BigDecimal required) {
        mockDueTask();
        when(lotteryService.prepareAutoProxyBet(10L, "M-1", "大1"))
                .thenReturn(Map.of("balance", balance, "requiredAmount", required));
    }

    private void mockDueTask() {
        AutoProxyExecutionDO task = new AutoProxyExecutionDO();
        task.setId("T-1");
        task.setUserId(10L);
        task.setMemberId("M-1");
        task.setPeriod("20260809001");
        task.setStatus("SCHEDULED");
        task.setVersion(0);
        task.setAttemptCount(0);
        when(executionMapper.selectOne(any())).thenReturn(task);
        when(executionMapper.update(any(), any())).thenReturn(1);
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(preset()));
    }

    private PresetOrderDO preset() {
        PresetOrderDO preset = new PresetOrderDO();
        preset.setId("P-1");
        preset.setContent("大1");
        preset.setEnabled(false);
        return preset;
    }

    private PresetOrderDO preset(String id, String content) {
        PresetOrderDO preset = new PresetOrderDO();
        preset.setId(id);
        preset.setContent(content);
        preset.setEnabled(false);
        return preset;
    }

    private MemberDO member() {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("自动托1");
        member.setUserId(10L);
        member.setMemberType("BOT");
        member.setAutoProxy(true);
        member.setAutoBetEnabled(true);
        member.setAutoTopUpAmount(new BigDecimal("1000"));
        return member;
    }

}
