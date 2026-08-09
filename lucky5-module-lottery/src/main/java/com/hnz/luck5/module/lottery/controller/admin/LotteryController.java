package com.hnz.luck5.module.lottery.controller.admin;

import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.module.lottery.controller.admin.vo.LotteryReqVO;
import com.hnz.luck5.module.lottery.service.LotteryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.hnz.luck5.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - Lucky5")
@RestController
@RequestMapping("/lottery")
@Validated
public class LotteryController {

    @Resource
    private LotteryService lotteryService;

    @GetMapping("/bootstrap")
    @Operation(summary = "获取 Lucky5 初始化数据")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<Map<String, Object>> getBootstrap() {
        return success(lotteryService.getBootstrap());
    }

    @PatchMapping("/switches/{key}")
    @PreAuthorize("@ss.hasPermission('lottery:dashboard:query')")
    public CommonResult<Boolean> setSwitch(@PathVariable String key, @Valid @RequestBody LotteryReqVO.BooleanValue reqVO) {
        lotteryService.setSwitch(key, reqVO.getValue());
        return success(true);
    }

    @PatchMapping("/room")
    @PreAuthorize("@ss.hasPermission('lottery:dashboard:query')")
    public CommonResult<Boolean> setRoom(@Valid @RequestBody LotteryReqVO.Room reqVO) {
        lotteryService.setRoom(reqVO.getOpen());
        return success(true);
    }

    @PutMapping("/config")
    @PreAuthorize("@ss.hasPermission('lottery:config:manage')")
    public CommonResult<Boolean> saveConfig(@Valid @RequestBody LotteryReqVO.Config reqVO) {
        lotteryService.saveConfig(reqVO);
        return success(true);
    }

    @PostMapping("/config/test")
    @PreAuthorize("@ss.hasPermission('lottery:config:manage')")
    public CommonResult<Map<String, Object>> testConfig(@Valid @RequestBody LotteryReqVO.Config reqVO) {
        return success(lotteryService.testConfig(reqVO));
    }

    @PostMapping("/config/sync")
    @PreAuthorize("@ss.hasPermission('lottery:config:manage')")
    public CommonResult<Map<String, Object>> syncMarket() {
        return success(lotteryService.syncMarket());
    }

    @PutMapping("/links")
    @PreAuthorize("@ss.hasPermission('lottery:link:manage')")
    public CommonResult<Boolean> saveLinks(@Valid @RequestBody LotteryReqVO.LinkConfig reqVO) {
        lotteryService.saveLinks(reqVO);
        return success(true);
    }

    @PutMapping("/chima-config")
    @PreAuthorize("@ss.hasPermission('lottery:chima-config:manage')")
    public CommonResult<Boolean> saveChimaConfig(@Valid @RequestBody LotteryReqVO.ChimaConfig reqVO) {
        lotteryService.saveChimaConfig(reqVO);
        return success(true);
    }

    @PutMapping("/integrations/{key}")
    @PreAuthorize("@ss.hasPermission('lottery:dashboard:query')")
    public CommonResult<Boolean> bindIntegration(@PathVariable String key, @Valid @RequestBody LotteryReqVO.Integration reqVO) {
        lotteryService.bindIntegration(key, reqVO);
        return success(true);
    }

    @DeleteMapping("/integrations/{key}")
    @PreAuthorize("@ss.hasPermission('lottery:dashboard:query')")
    public CommonResult<Boolean> unbindIntegration(@PathVariable String key) {
        lotteryService.unbindIntegration(key);
        return success(true);
    }

    @PostMapping("/members")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<String> createMember(@Valid @RequestBody LotteryReqVO.Member reqVO) {
        return success(lotteryService.saveMember(reqVO));
    }

    @PutMapping("/members/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<String> updateMember(@PathVariable String id, @Valid @RequestBody LotteryReqVO.Member reqVO) {
        reqVO.setId(id);
        return success(lotteryService.saveMember(reqVO));
    }

    @PostMapping("/members/{id}/transfer")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Boolean> transferMember(@PathVariable String id, @Valid @RequestBody LotteryReqVO.Transfer reqVO) {
        lotteryService.transferMember(id, reqVO);
        return success(true);
    }

    @PostMapping("/members/{id}/amount-request")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<String> createAmountRequest(@PathVariable String id, @Valid @RequestBody LotteryReqVO.Transfer reqVO) {
        return success(lotteryService.createAmountRequest(id, reqVO));
    }

    @GetMapping("/members/{id}/details")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Map<String, Object>> getMemberDetails(@PathVariable String id) {
        return success(lotteryService.getMemberDetails(id));
    }

    @GetMapping("/members/{id}/links")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Map<String, String>> getMemberLinks(@PathVariable String id,
                                                             @RequestHeader(value = "Origin", required = false) String origin) {
        return success(lotteryService.getMemberLinks(id, origin));
    }

    @PostMapping("/members/{id}/rotate-link")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Map<String, String>> rotateMemberLink(@PathVariable String id,
                                                               @RequestHeader(value = "Origin", required = false) String origin) {
        return success(lotteryService.rotateMemberLink(id, origin));
    }

    @PostMapping("/members/{id}/clear-fingerprint")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Boolean> clearMemberFingerprint(@PathVariable String id) {
        lotteryService.clearMemberFingerprint(id);
        return success(true);
    }

    @PostMapping("/members/clear-fingerprints")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Integer> clearAllFingerprints() {
        return success(lotteryService.clearAllFingerprints());
    }

    @PostMapping("/members/{id}/change-avatar")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Integer> changeMemberAvatar(@PathVariable String id) {
        return success(lotteryService.changeMemberAvatar(id));
    }

    @PostMapping("/members/clear-flows")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Integer> clearAllMemberFlows(@Valid @RequestBody LotteryReqVO.Password reqVO) {
        return success(lotteryService.clearAllMemberFlows(reqVO.getPassword()));
    }

    @PostMapping("/members/{id}/clear")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Boolean> clearMember(@PathVariable String id) {
        lotteryService.clearMember(id);
        return success(true);
    }

    @DeleteMapping("/members/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:member:manage')")
    public CommonResult<Boolean> deleteMember(@PathVariable String id) {
        lotteryService.deleteMember(id);
        return success(true);
    }

    @PutMapping("/member-discounts")
    @PreAuthorize("@ss.hasPermission('lottery:rebate:manage')")
    public CommonResult<Boolean> saveDiscounts(@Valid @RequestBody LotteryReqVO.Discounts reqVO) {
        lotteryService.saveDiscounts(reqVO);
        return success(true);
    }

    @PostMapping("/rebates/apply")
    @PreAuthorize("@ss.hasPermission('lottery:rebate:manage')")
    public CommonResult<Map<String, Object>> applyRebates() {
        return success(lotteryService.applyRebates());
    }

    @PostMapping("/chima-records/clear")
    @PreAuthorize("@ss.hasPermission('lottery:chima-record:manage')")
    public CommonResult<Boolean> clearChimaRecords(@Valid @RequestBody LotteryReqVO.Password reqVO) {
        lotteryService.clearChimaRecords(reqVO.getPassword());
        return success(true);
    }

    @PostMapping("/amount-records/{id}/audit")
    @PreAuthorize("@ss.hasPermission('lottery:amount:manage')")
    public CommonResult<Boolean> auditAmount(@PathVariable String id, @Valid @RequestBody LotteryReqVO.Audit reqVO) {
        lotteryService.auditAmount(id, reqVO);
        return success(true);
    }

    @PostMapping("/bets")
    @PreAuthorize("@ss.hasPermission('lottery:order:manage')")
    public CommonResult<Map<String, Object>> placeBet(@Valid @RequestBody LotteryReqVO.PlaceBet reqVO) {
        return success(lotteryService.placeBet(reqVO));
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("@ss.hasPermission('lottery:order:manage')")
    public CommonResult<Map<String, Object>> cancelOrder(@PathVariable String id) {
        return success(lotteryService.cancelOrder(id));
    }

    @PostMapping("/draws/{period}/settle")
    @PreAuthorize("@ss.hasPermission('lottery:draw:manage')")
    public CommonResult<Map<String, Object>> settlePeriod(@PathVariable String period, @RequestBody LotteryReqVO.Settle reqVO) {
        return success(lotteryService.settlePeriod(period, reqVO));
    }

    @GetMapping("/issues/status")
    @PreAuthorize("@ss.hasPermission('lottery:draw:manage')")
    public CommonResult<Map<String, Object>> getIssueStatus() {
        return success(lotteryService.getIssueStatus());
    }

    @PostMapping("/issues/{period}/{status}")
    @PreAuthorize("@ss.hasPermission('lottery:draw:manage')")
    public CommonResult<Boolean> setIssueStatus(@PathVariable String period, @PathVariable String status) {
        lotteryService.setIssueStatus(period, status);
        return success(true);
    }

    @PostMapping("/issues/settle-pending")
    @PreAuthorize("@ss.hasPermission('lottery:draw:manage')")
    public CommonResult<Integer> settlePendingIssues() {
        return success(lotteryService.settlePendingIssues());
    }

    @PostMapping("/messages/incoming")
    @PreAuthorize("@ss.hasPermission('lottery:message:manage')")
    public CommonResult<Map<String, Object>> processIncomingMessage(@Valid @RequestBody LotteryReqVO.IncomingMessage reqVO) {
        return success(lotteryService.processIncomingMessage(reqVO));
    }

    @PutMapping("/odds")
    @PreAuthorize("@ss.hasPermission('lottery:odds:manage')")
    public CommonResult<Boolean> saveOdds(@Valid @RequestBody LotteryReqVO.Odds reqVO) {
        lotteryService.saveOdds(reqVO);
        return success(true);
    }

    @PostMapping("/fake-orders")
    @PreAuthorize("@ss.hasPermission('lottery:preset:manage')")
    public CommonResult<String> createPresetOrder(@Valid @RequestBody LotteryReqVO.PresetOrder reqVO) {
        return success(lotteryService.savePresetOrder(reqVO));
    }

    @PutMapping("/fake-orders/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:preset:manage')")
    public CommonResult<String> updatePresetOrder(@PathVariable String id, @Valid @RequestBody LotteryReqVO.PresetOrder reqVO) {
        reqVO.setId(id);
        return success(lotteryService.savePresetOrder(reqVO));
    }

    @DeleteMapping("/fake-orders/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:preset:manage')")
    public CommonResult<Boolean> deletePresetOrder(@PathVariable String id) {
        lotteryService.deletePresetOrder(id);
        return success(true);
    }

    @PostMapping("/quick-commands")
    @PreAuthorize("@ss.hasPermission('lottery:quick-command:manage')")
    public CommonResult<String> createQuickCommand(@Valid @RequestBody LotteryReqVO.QuickCommand reqVO) {
        return success(lotteryService.saveQuickCommand(reqVO));
    }

    @PutMapping("/quick-commands/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:quick-command:manage')")
    public CommonResult<String> updateQuickCommand(@PathVariable String id, @Valid @RequestBody LotteryReqVO.QuickCommand reqVO) {
        reqVO.setId(id);
        return success(lotteryService.saveQuickCommand(reqVO));
    }

    @DeleteMapping("/quick-commands/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:quick-command:manage')")
    public CommonResult<Boolean> deleteQuickCommand(@PathVariable String id) {
        lotteryService.deleteQuickCommand(id);
        return success(true);
    }

    @PostMapping("/follow-orders")
    @PreAuthorize("@ss.hasPermission('lottery:follow:manage')")
    public CommonResult<String> createFollowOrder(@Valid @RequestBody LotteryReqVO.FollowOrder reqVO) {
        return success(lotteryService.saveFollowOrder(reqVO));
    }

    @PutMapping("/follow-orders/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:follow:manage')")
    public CommonResult<String> updateFollowOrder(@PathVariable String id, @Valid @RequestBody LotteryReqVO.FollowOrder reqVO) {
        reqVO.setId(id);
        return success(lotteryService.saveFollowOrder(reqVO));
    }

    @DeleteMapping("/follow-orders/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:follow:manage')")
    public CommonResult<Boolean> deleteFollowOrder(@PathVariable String id) {
        lotteryService.deleteFollowOrder(id);
        return success(true);
    }

    @PatchMapping("/messages/{id}")
    @PreAuthorize("@ss.hasPermission('lottery:message:manage')")
    public CommonResult<Boolean> markMessage(@PathVariable Long id, @Valid @RequestBody LotteryReqVO.MessageStatus reqVO) {
        lotteryService.markMessage(id, reqVO.getStatus());
        return success(true);
    }
}
