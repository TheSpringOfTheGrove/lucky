package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.dal.dataobject.IssueDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.IssueMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryPeriodSequenceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-period-sequence-issue-test"),
                IssueDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lottery-period-sequence-order-test"),
                OrderDO.class);
    }

    private LotteryServiceImpl service;
    private IssueMapper issueMapper;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        issueMapper = mock(IssueMapper.class);
        orderMapper = mock(OrderMapper.class);
        ReflectionTestUtils.setField(service, "issueMapper", issueMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "issueFreshnessPolicy", new LotteryIssueFreshnessPolicy());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allocationRecoversFromCounterBehindExistingOrders() {
        IssueDO issue = new IssueDO();
        issue.setId(1L);
        issue.setUserId(1L);
        issue.setPeriod("20260818228");
        issue.setStatus("OPEN");
        issue.setOrderSequence(0);
        issue.setSourceObservedAt(LocalDateTime.now());
        issue.setRemainingSeconds(60);
        when(issueMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(issue);
        when(orderMapper.selectObjs(any(QueryWrapper.class))).thenReturn(List.of(1));
        when(issueMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        Integer sequence = ReflectionTestUtils.invokeMethod(service, "allocatePeriodSequence", 1L, "20260818228");

        assertThat(sequence).isEqualTo(2);
        verify(issueMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void ordinaryIssueUpdatesCannotOverwriteOrderSequence() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(IssueDO.class);

        assertThat(tableInfo.getFieldList()).filteredOn(field -> "orderSequence".equals(field.getProperty()))
                .singleElement().extracting("updateStrategy").isEqualTo(FieldStrategy.NEVER);
    }
}
