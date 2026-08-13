package com.hnz.luck5.module.lottery.controller.admin;

import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.service.LotteryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotteryControllerConfigTest {

    @Test
    void saveConfigImmediatelyVerifiesAndReturnsConnection() {
        LotteryService service = mock(LotteryService.class);
        LotteryController controller = new LotteryController();
        ReflectionTestUtils.setField(controller, "lotteryService", service);
        LotteryReqVO.Config request = new LotteryReqVO.Config();
        Map<String, Object> connection = Map.of("status", "connected", "balance", 975);
        when(service.verifyMarketConnection()).thenReturn(connection);

        CommonResult<Map<String, Object>> result = controller.saveConfig(request);

        InOrder order = inOrder(service);
        order.verify(service).saveConfig(request);
        order.verify(service).verifyMarketConnection();
        assertThat(result.getData()).isEqualTo(connection);
    }
}
