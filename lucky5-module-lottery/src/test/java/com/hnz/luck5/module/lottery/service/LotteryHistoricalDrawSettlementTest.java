package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.DrawDO;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.DrawMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.IssueTransitionMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryHistoricalDrawSettlementTest {

    private LotteryServiceImpl service;
    private DrawMapper drawMapper;
    private IssueMapper issueMapper;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : new Class<?>[]{DrawDO.class, IssueDO.class, OrderDO.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, type.getSimpleName()), type);
        }
        service = new LotteryServiceImpl();
        drawMapper = mock(DrawMapper.class);
        issueMapper = mock(IssueMapper.class);
        orderMapper = mock(OrderMapper.class);
        ReflectionTestUtils.setField(service, "bettingService", new LotteryBettingService());
        ReflectionTestUtils.setField(service, "drawMapper", drawMapper);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "issueTransitionMapper", mock(IssueTransitionMapper.class));
    }

    @Test
    void completesVerifiedUnopenedHistoryWithoutOrders() {
        IssueDO issue = verifiedIssue();
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(issueMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.selectCount(any())).thenReturn(0L);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        ArgumentCaptor<DrawDO> saved = ArgumentCaptor.forClass(DrawDO.class);
        verify(drawMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("已结算");
        assertThat(saved.getValue().getSettledAt()).isNotNull();
        assertThat(issue.getStatus()).isEqualTo("SETTLED");
        verify(issueMapper).update(isNull(), any());
    }

    @Test
    void repairsPreviouslyPendingUnopenedHistory() {
        IssueDO issue = verifiedIssue();
        DrawDO pending = pendingDraw(issue);
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(issueMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(drawMapper.selectOne(any())).thenReturn(pending);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        assertThat(pending.getStatus()).isEqualTo("已结算");
        assertThat(pending.getSettledAt()).isNotNull();
        verify(drawMapper).updateById(pending);
    }

    @Test
    void keepsFormallyOpenedPeriodPendingEvenWithoutOrders() {
        IssueDO issue = verifiedIssue();
        issue.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        when(issueMapper.selectOne(any())).thenReturn(issue);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        ArgumentCaptor<DrawDO> saved = ArgumentCaptor.forClass(DrawDO.class);
        verify(drawMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("待结算");
        verify(issueMapper, never()).update(isNull(), any());
        verify(orderMapper, never()).selectCount(any());
    }

    @Test
    void keepsUnopenedPeriodWithAnOrderPending() {
        IssueDO issue = verifiedIssue();
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(orderMapper.selectCount(any())).thenReturn(1L);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        ArgumentCaptor<DrawDO> saved = ArgumentCaptor.forClass(DrawDO.class);
        verify(drawMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("待结算");
        verify(issueMapper, never()).update(isNull(), any());
    }

    @Test
    void repairsPendingDrawWhenAnOpenedIssueWasAlreadySettled() {
        IssueDO issue = verifiedIssue();
        issue.setStatus("SETTLED");
        issue.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        issue.setSettledAt(LocalDateTime.now().minusMinutes(1));
        DrawDO pending = pendingDraw(issue);
        when(issueMapper.selectOne(any())).thenReturn(issue);
        when(drawMapper.selectOne(any())).thenReturn(pending);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        assertThat(pending.getStatus()).isEqualTo("已结算");
        assertThat(pending.getSettledAt()).isEqualTo(issue.getSettledAt());
        verify(drawMapper).updateById(pending);
        verify(orderMapper, never()).selectCount(any());
    }

    @Test
    void createsSettledDrawWhenAnOpenedIssueWasAlreadySettled() {
        IssueDO issue = verifiedIssue();
        issue.setStatus("SETTLED");
        issue.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        issue.setSettledAt(LocalDateTime.now().minusMinutes(1));
        when(issueMapper.selectOne(any())).thenReturn(issue);

        service.publishVerifiedDrawForUser(229L, issue.getPeriod(), issue.getResult());

        ArgumentCaptor<DrawDO> saved = ArgumentCaptor.forClass(DrawDO.class);
        verify(drawMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("已结算");
        assertThat(saved.getValue().getSettledAt()).isEqualTo(issue.getSettledAt());
    }

    private IssueDO verifiedIssue() {
        IssueDO issue = new IssueDO();
        issue.setId(41L);
        issue.setUserId(229L);
        issue.setPeriod("20261022188");
        issue.setStatus("DRAWN");
        issue.setResult("62845");
        issue.setDrawConfirmations(2);
        return issue;
    }

    private DrawDO pendingDraw(IssueDO issue) {
        DrawDO draw = new DrawDO();
        draw.setId(42L);
        draw.setUserId(issue.getUserId());
        draw.setPeriod(issue.getPeriod());
        draw.setResult("6,2,8,4,5");
        draw.setStatus("待结算");
        return draw;
    }
}
