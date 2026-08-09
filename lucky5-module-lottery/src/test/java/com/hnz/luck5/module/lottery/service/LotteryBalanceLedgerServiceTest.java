package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.exception.ServiceException;
import com.hnz.luck5.module.lottery.dal.dataobject.BalanceLedgerDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.BalanceLedgerMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_STATE_CHANGED;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MEMBER_BALANCE_NOT_ENOUGH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryBalanceLedgerServiceTest {

    @Mock private MemberMapper memberMapper;
    @Mock private BalanceLedgerMapper balanceLedgerMapper;

    @InjectMocks
    private LotteryBalanceLedgerService service;

    @Test
    void debitUpdatesBalanceWithVersionAndWritesLedger() {
        MemberDO member = member("100.00", 3);
        when(memberMapper.update(any(), any())).thenReturn(1);

        LotteryBalanceLedgerService.BalanceChange change = service.change(member, new BigDecimal("-25"),
                LotteryBalanceLedgerService.BET_DEBIT, "O-1", "boss", "下注");

        assertThat(change.before()).isEqualByComparingTo("100.00");
        assertThat(change.after()).isEqualByComparingTo("75.00");
        assertThat(member.getBalance()).isEqualByComparingTo("75.00");
        assertThat(member.getVersion()).isEqualTo(4);
        ArgumentCaptor<BalanceLedgerDO> captor = ArgumentCaptor.forClass(BalanceLedgerDO.class);
        verify(balanceLedgerMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(BalanceLedgerDO::getBusinessType, BalanceLedgerDO::getBusinessId,
                        BalanceLedgerDO::getDirection, BalanceLedgerDO::getAmount,
                        BalanceLedgerDO::getBalanceBefore, BalanceLedgerDO::getBalanceAfter)
                .containsExactly(LotteryBalanceLedgerService.BET_DEBIT, "O-1", "DEBIT",
                        new BigDecimal("25.00"), new BigDecimal("100.00"), new BigDecimal("75.00"));
    }

    @Test
    void refusesOverdraftWithoutWritingLedger() {
        MemberDO member = member("10.00", 0);

        assertThatThrownBy(() -> service.change(member, new BigDecimal("-10.01"),
                LotteryBalanceLedgerService.WITHDRAW, "AR-1", "boss", "下分"))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(MEMBER_BALANCE_NOT_ENOUGH.getCode()));
        verify(memberMapper, never()).update(any(), any());
        verify(balanceLedgerMapper, never()).insert(any(BalanceLedgerDO.class));
    }

    @Test
    void doesNotWriteLedgerWhenOptimisticUpdateLosesRace() {
        MemberDO member = member("100.00", 2);
        when(memberMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.change(member, BigDecimal.TEN,
                LotteryBalanceLedgerService.DEPOSIT, "AR-2", "boss", "上分"))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BET_STATE_CHANGED.getCode()));
        verify(balanceLedgerMapper, never()).insert(any(BalanceLedgerDO.class));
    }

    private MemberDO member(String balance, int version) {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setUserId(10L);
        member.setName("玩家1");
        member.setBalance(new BigDecimal(balance));
        member.setVersion(version);
        return member;
    }

}
