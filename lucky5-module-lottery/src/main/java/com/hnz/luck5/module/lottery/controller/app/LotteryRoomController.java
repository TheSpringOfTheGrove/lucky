package com.hnz.luck5.module.lottery.controller.app;

import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.framework.tenant.core.aop.TenantIgnore;
import com.hnz.luck5.module.lottery.controller.app.vo.LotteryRoomReqVO;
import com.hnz.luck5.module.lottery.service.LotteryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.hnz.luck5.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - Lucky5 会员房间")
@RestController
@RequestMapping("/lottery/room")
@Validated
@TenantIgnore
@PermitAll
public class LotteryRoomController {

    @Resource
    private LotteryService lotteryService;

    @GetMapping("/session")
    public CommonResult<Map<String, Object>> getSession(@Valid LotteryRoomReqVO.Credential reqVO) {
        return success(lotteryService.getRoomSession(reqVO));
    }

    @PostMapping("/bets")
    public CommonResult<Map<String, Object>> placeBet(@Valid @RequestBody LotteryRoomReqVO.Bet reqVO) {
        return success(lotteryService.roomPlaceBet(reqVO));
    }

    @PostMapping("/bets/preview")
    public CommonResult<Map<String, Object>> previewBet(@Valid @RequestBody LotteryRoomReqVO.Bet reqVO) {
        return success(lotteryService.previewRoomBet(reqVO));
    }

    @PostMapping("/messages")
    public CommonResult<Map<String, Object>> processMessage(@Valid @RequestBody LotteryRoomReqVO.Message reqVO) {
        return success(lotteryService.processRoomMessage(reqVO));
    }

    @PostMapping("/amount-requests")
    public CommonResult<Map<String, Object>> createAmountRequest(@Valid @RequestBody LotteryRoomReqVO.Amount reqVO) {
        return success(lotteryService.createRoomAmountRequest(reqVO));
    }

    @PostMapping("/orders/{id}/cancel")
    public CommonResult<Map<String, Object>> cancelOrder(@PathVariable String id, @Valid @RequestBody LotteryRoomReqVO.Credential reqVO) {
        return success(lotteryService.cancelRoomOrder(id, reqVO));
    }
}
