package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MessageMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    @Mock private PresetOrderMapper presetOrderMapper;
    @Mock private MemberMapper memberMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private LotteryService lotteryService;

    @InjectMocks
    private LotteryAutoProxyService service;

    @Test
    void retriesNextPresetInAnIndependentBetAndRecordsFailure() {
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(preset("P-1", "大1"), preset("P-2", "小1")));
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(lotteryService.placeAutoBet(anyLong(), any(), anyString()))
                .thenThrow(new IllegalStateException("模板暂不可用")).thenReturn(Map.of("orderId", "O-1"));

        LotteryAutoProxyService.RunResult result = service.run(10L, "20260809001");

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.placed()).isEqualTo(1);
        ArgumentCaptor<LotteryReqVO.PlaceBet> betCaptor = ArgumentCaptor.forClass(LotteryReqVO.PlaceBet.class);
        verify(lotteryService, times(2)).placeAutoBet(anyLong(), betCaptor.capture(), anyString());
        assertThat(betCaptor.getAllValues()).allSatisfy(bet -> {
            assertThat(bet.getChannel()).isEqualTo("网页群");
            assertThat(bet.getExternalId()).startsWith("auto-proxy:").endsWith(":20260809001");
            assertThat(bet.getExternalId()).hasSizeLessThanOrEqualTo(100);
        });
        assertThat(betCaptor.getAllValues()).extracting(LotteryReqVO.PlaceBet::getExternalId).containsOnly(
                betCaptor.getAllValues().get(0).getExternalId());
        ArgumentCaptor<MessageDO> failureCaptor = ArgumentCaptor.forClass(MessageDO.class);
        verify(messageMapper).insert(failureCaptor.capture());
        assertThat(failureCaptor.getValue().getExternalId()).startsWith("auto-proxy-failure:20260809001:");
    }

    @Test
    void skipsMemberWhenPeriodAlreadyHasCompletedAutoOrder() {
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(preset("P-1", "大1")));
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        MessageDO completed = new MessageDO();
        completed.setOrderId("O-1");
        when(messageMapper.selectOne(any())).thenReturn(completed);

        LotteryAutoProxyService.RunResult result = service.run(10L, "20260809001");

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.placed()).isZero();
        verify(lotteryService, never()).placeAutoBet(anyLong(), any(), anyString());
        verify(messageMapper, never()).insert(any(MessageDO.class));
    }

    @Test
    void duplicateReturnedByIndependentBetIsNotCountedAsNewPlacement() {
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(preset("P-1", "大1")));
        when(memberMapper.selectList(any())).thenReturn(List.of(member()));
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(lotteryService.placeAutoBet(anyLong(), any(), anyString()))
                .thenReturn(Map.of("orderId", "O-1", "duplicate", true));

        LotteryAutoProxyService.RunResult result = service.run(10L, "20260809001");

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.placed()).isZero();
        verify(lotteryService).placeAutoBet(anyLong(), any(), anyString());
    }

    private PresetOrderDO preset(String id, String content) {
        PresetOrderDO preset = new PresetOrderDO();
        preset.setId(id);
        preset.setContent(content);
        preset.setEnabled(true);
        return preset;
    }

    private MemberDO member() {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setName("玩家1");
        member.setUserId(10L);
        member.setAutoProxy(true);
        return member;
    }

}
