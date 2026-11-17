package com.hnz.luck5.module.lottery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.LotteryConfigMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.OperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryDefaultRebateRatesTest {

    @Mock
    private LotteryConfigMapper lotteryConfigMapper;
    @Mock
    private MemberMapper memberMapper;
    @Mock
    private OperationLogMapper operationLogMapper;

    private LotteryServiceImpl service;
    private LotteryConfigDO config;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
                "default-rebate-member-test"), MemberDO.class);
        service = new LotteryServiceImpl();
        ReflectionTestUtils.setField(service, "lotteryConfigMapper", lotteryConfigMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "operationLogMapper", operationLogMapper);

        config = new LotteryConfigDO();
        config.setId(7L);
        config.setUserId(1L);
        when(lotteryConfigMapper.selectOne(any())).thenReturn(config);
    }

    @Test
    void savesDefaultsAndSynchronizesExistingRealMembers() {
        LotteryReqVO.RebateDefaults request = new LotteryReqVO.RebateDefaults();
        request.setNormalRate(new BigDecimal("1.2"));
        request.setLhhRate(new BigDecimal("0.8"));

        service.saveDefaultRebateRates(request);

        assertThat(config.getDefaultNormalRebateRate()).isEqualByComparingTo("1.2");
        assertThat(config.getDefaultLhhRebateRate()).isEqualByComparingTo("0.8");
        verify(lotteryConfigMapper).updateById(config);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<MemberDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(memberMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<MemberDO> wrapper = (LambdaUpdateWrapper<MemberDO>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("normal_rate", "lhh_rate");
        assertThat(wrapper.getSqlSegment()).contains("user_id", "member_type", "auto_proxy");
    }

    @Test
    void returnsSavedDefaultsForDialogDisplay() {
        config.setDefaultNormalRebateRate(new BigDecimal("2.5"));
        config.setDefaultLhhRebateRate(new BigDecimal("1.5"));

        Map<String, Object> result = service.getDefaultRebateRates();

        assertThat((BigDecimal) result.get("normalRate")).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) result.get("lhhRate")).isEqualByComparingTo("1.5");
    }

    @Test
    void newRealMemberAlwaysInheritsSavedDefaults() {
        config.setDefaultNormalRebateRate(new BigDecimal("1.6"));
        config.setDefaultLhhRebateRate(new BigDecimal("0.9"));
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(memberMapper.insert(any(MemberDO.class))).thenReturn(1);

        LotteryReqVO.Member request = new LotteryReqVO.Member();
        request.setName("新会员");
        request.setBalance(BigDecimal.ZERO);
        request.setNormalRate(BigDecimal.ZERO);
        request.setLhhRate(BigDecimal.ZERO);
        request.setAutoProxy(false);

        service.saveMember(request);

        ArgumentCaptor<MemberDO> memberCaptor = ArgumentCaptor.forClass(MemberDO.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getNormalRate()).isEqualByComparingTo("1.6");
        assertThat(memberCaptor.getValue().getLhhRate()).isEqualByComparingTo("0.9");
    }
}
