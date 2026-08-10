package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OperationLogDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OperationLogMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryMemberModeTest {

    @Mock
    private MemberMapper memberMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private OrderMapper orderMapper;

    private LotteryServiceImpl service;
    private MemberDO member;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "operationLogMapper", operationLogMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);

        member = new MemberDO();
        member.setId("M-1");
        member.setUserId(142L);
        member.setName("测试会员");
        member.setBalance(new BigDecimal("100"));
        member.setMemberType("REAL");
        member.setAutoProxy(false);
        member.setAutoBetEnabled(false);
        member.setEatEnabled(true);
        member.setVersion(0);

        when(memberMapper.selectOne(any())).thenReturn(null);
        when(memberMapper.selectById("M-1")).thenReturn(member);
        when(memberMapper.update(any(), any())).thenReturn(1);
        when(operationLogMapper.insert(any(OperationLogDO.class))).thenReturn(1);
    }

    @Test
    void autoProxyAndEatCanBeSwitchedIndependently() {
        LotteryReqVO.Member enableProxy = request(true, null);
        service.saveMember(enableProxy);

        assertThat(member.getMemberType()).isEqualTo("BOT");
        assertThat(member.getAutoProxy()).isTrue();
        assertThat(member.getAutoBetEnabled()).isTrue();
        assertThat(member.getEatEnabled()).isTrue();

        LotteryReqVO.Member disableEat = request(true, false);
        service.saveMember(disableEat);

        assertThat(member.getMemberType()).isEqualTo("BOT");
        assertThat(member.getAutoProxy()).isTrue();
        assertThat(member.getAutoBetEnabled()).isTrue();
        assertThat(member.getEatEnabled()).isFalse();

        LotteryReqVO.Member disableProxy = request(false, null);
        service.saveMember(disableProxy);

        assertThat(member.getMemberType()).isEqualTo("REAL");
        assertThat(member.getAutoProxy()).isFalse();
        assertThat(member.getAutoBetEnabled()).isFalse();
        assertThat(member.getEatEnabled()).isFalse();
        verifyNoInteractions(orderMapper);
    }

    private LotteryReqVO.Member request(boolean autoProxy, Boolean eatEnabled) {
        LotteryReqVO.Member request = new LotteryReqVO.Member();
        request.setId(member.getId());
        request.setName(member.getName());
        request.setBalance(member.getBalance());
        request.setAutoProxy(autoProxy);
        request.setEatEnabled(eatEnabled);
        return request;
    }
}
