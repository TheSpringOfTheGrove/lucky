package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryMemberSnapshotTest {

    @Mock
    private MemberMapper memberMapper;

    private LotteryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LotteryServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
    }

    @Test
    void snapshotExposesImmediateBetTotalsWithoutRecalculatingSettlement() {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setBalance(new BigDecimal("75.00"));
        member.setTotalBet(new BigDecimal("125.50"));
        member.setProfitLoss(new BigDecimal("-25.00"));
        member.setStatus("ONLINE");
        member.setLastSeenAt(LocalDateTime.now());
        member.setVersion(3);
        when(memberMapper.selectList(any())).thenReturn(List.of(member));

        List<Map<String, Object>> snapshots = service.getMemberSnapshots();

        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.get("id")).isEqualTo("M-1");
            assertThat(snapshot.get("balance")).isEqualTo(new BigDecimal("75.00"));
            assertThat(snapshot.get("totalBet")).isEqualTo(new BigDecimal("125.50"));
            assertThat(snapshot.get("profitLoss")).isEqualTo(new BigDecimal("-25.00"));
            assertThat(snapshot.get("status")).isEqualTo("在线");
            assertThat(snapshot.get("version")).isEqualTo(3);
        });
    }

    @Test
    void snapshotTreatsStalePresenceAsOfflineEvenWhenLegacyStatusSaysOnline() {
        MemberDO member = new MemberDO();
        member.setId("M-2");
        member.setStatus("在线");
        member.setLastSeenAt(LocalDateTime.now().minusMinutes(2));
        when(memberMapper.selectList(any())).thenReturn(List.of(member));

        assertThat(service.getMemberSnapshots()).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.get("status")).isEqualTo("离线"));
    }
}
