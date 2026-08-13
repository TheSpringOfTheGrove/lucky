package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.framework.common.exception.ServiceException;
import com.hnz.luck5.module.lottery.dal.dataobject.LotteryConfigDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.MARKET_BALANCE_NOT_ENOUGH;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class LotteryMarketBalanceServiceTest {

    private final Wa55MarketClient marketClient = mock(Wa55MarketClient.class);
    private final MarketCredentialService credentialService = mock(MarketCredentialService.class);
    private final LotteryMarketBalanceService service = new LotteryMarketBalanceService();
    private final LotteryConfigDO config = new LotteryConfigDO();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "marketClient", marketClient);
        ReflectionTestUtils.setField(service, "credentialService", credentialService);
        config.setUpstreamUrl("https://market.example");
        config.setUpstreamAccount("owner");
        config.setMarketPasswordEncrypted("encrypted");
        when(credentialService.decrypt("encrypted")).thenReturn("password");
    }

    @Test
    void rejectsBeforeOrderingWhenExternalBalanceIsInsufficient() {
        when(marketClient.read(any(), eq(false))).thenReturn(snapshot("8.99"));

        assertThatThrownBy(() -> service.requireSufficient(config, new BigDecimal("9.00")))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode())
                                .isEqualTo(MARKET_BALANCE_NOT_ENOUGH.getCode()));
    }

    @Test
    void allowsEqualBalanceAndSkipsReadForFullyLocalRouting() {
        when(marketClient.read(any(), eq(false))).thenReturn(snapshot("9.00"));

        assertThatCode(() -> service.requireSufficient(config, new BigDecimal("9.00")))
                .doesNotThrowAnyException();
        verify(marketClient).read(any(), eq(false));

        reset(marketClient);
        service.requireSufficient(config, BigDecimal.ZERO);
        verifyNoInteractions(marketClient);
    }

    private Wa55MarketClient.Snapshot snapshot(String balance) {
        return new Wa55MarketClient.Snapshot("https://market.example",
                new Wa55MarketClient.Account("owner", new BigDecimal(balance)), null, List.of());
    }
}
