package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MARKET_ACCOUNT_NOT_READY;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MARKET_BALANCE_NOT_ENOUGH;

/** Performs a read-only, real-time balance check before an external-market order can be created. */
@Service
public class LotteryMarketBalanceService {

    @Resource private Wa55MarketClient marketClient;
    @Resource private MarketCredentialService credentialService;
    @Resource private LotteryMarketAccountLockService accountLockService;

    public void requireSufficient(LotteryConfigDO config, BigDecimal requiredAmount) {
        if (requiredAmount == null || requiredAmount.signum() <= 0) {
            return;
        }
        Wa55MarketClient.Snapshot snapshot = accountLockService.execute(config.getTenantId(), config.getUserId(),
                () -> marketClient.read(new Wa55MarketClient.Credentials(
                        config.getUpstreamUrl(), config.getUpstreamAccount(),
                        credentialService.decrypt(config.getMarketPasswordEncrypted())), false));
        BigDecimal balance = snapshot.account() == null ? null : snapshot.account().balance();
        if (balance == null) {
            throw exception(MARKET_ACCOUNT_NOT_READY);
        }
        if (balance.compareTo(requiredAmount) < 0) {
            throw exception(MARKET_BALANCE_NOT_ENOUGH);
        }
    }
}
