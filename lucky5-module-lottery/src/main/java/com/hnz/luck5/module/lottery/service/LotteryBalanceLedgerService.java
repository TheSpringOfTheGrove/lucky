package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hnz.luck5.module.lottery.dal.dataobject.BalanceLedgerDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.mysql.BalanceLedgerMapper;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.BET_STATE_CHANGED;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MEMBER_BALANCE_NOT_ENOUGH;

/**
 * Applies simple balance changes and records every applied change in an immutable business ledger.
 */
@Service
@RequiredArgsConstructor
public class LotteryBalanceLedgerService {

    public static final String OPENING_BALANCE = "OPENING_BALANCE";
    public static final String MANUAL_ADJUSTMENT = "MANUAL_ADJUSTMENT";
    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String BET_DEBIT = "BET_DEBIT";
    public static final String BET_REFUND = "BET_REFUND";
    public static final String PAYOUT = "PAYOUT";
    public static final String REBATE = "REBATE";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MemberMapper memberMapper;
    private final BalanceLedgerMapper balanceLedgerMapper;

    public BalanceChange change(MemberDO member, BigDecimal delta, String businessType, String businessId,
                                 String actor, String remark) {
        BigDecimal normalizedDelta = money(delta);
        BigDecimal before = money(member.getBalance());
        BigDecimal after = money(before.add(normalizedDelta));
        if (after.signum() < 0) {
            throw exception(MEMBER_BALANCE_NOT_ENOUGH);
        }
        if (normalizedDelta.signum() == 0) {
            return new BalanceChange(before, after);
        }
        int version = member.getVersion() == null ? 0 : member.getVersion();
        UpdateWrapper<MemberDO> update = new UpdateWrapper<MemberDO>()
                .eq("id", member.getId()).eq("user_id", member.getUserId())
                .eq("version", version)
                .set("balance", after).set("version", version + 1);
        if (normalizedDelta.signum() < 0) {
            update.ge("balance", normalizedDelta.abs());
        }
        if (memberMapper.update(null, update) != 1) {
            throw exception(BET_STATE_CHANGED);
        }
        member.setBalance(after);
        member.setVersion(version + 1);
        recordAppliedChange(member, before, after, businessType, businessId, actor, remark);
        return new BalanceChange(before, after);
    }

    public void recordAppliedChange(MemberDO member, BigDecimal before, BigDecimal after, String businessType,
                                    String businessId, String actor, String remark) {
        BigDecimal normalizedBefore = money(before);
        BigDecimal normalizedAfter = money(after);
        BigDecimal delta = normalizedAfter.subtract(normalizedBefore);
        if (delta.signum() == 0) {
            return;
        }
        if (StrUtil.isBlank(businessType) || StrUtil.isBlank(businessId)) {
            throw new IllegalArgumentException("Balance ledger business identity is required");
        }
        BalanceLedgerDO ledger = new BalanceLedgerDO();
        ledger.setId(IdUtil.fastSimpleUUID());
        ledger.setMemberId(member.getId());
        ledger.setMemberName(member.getName());
        ledger.setBusinessType(businessType);
        ledger.setBusinessId(businessId);
        ledger.setDirection(delta.signum() > 0 ? "CREDIT" : "DEBIT");
        ledger.setAmount(money(delta.abs()));
        ledger.setBalanceBefore(normalizedBefore);
        ledger.setBalanceAfter(normalizedAfter);
        ledger.setActor(StrUtil.blankToDefault(actor, "system"));
        ledger.setRemark(StrUtil.blankToDefault(remark, ""));
        ledger.setUserId(member.getUserId());
        balanceLedgerMapper.insert(ledger);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public record BalanceChange(BigDecimal before, BigDecimal after) {
    }

}
