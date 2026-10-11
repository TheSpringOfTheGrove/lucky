package com.hnz.luck5.module.system.controller.admin.user;

import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.framework.common.pojo.PageParam;
import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.system.service.user.AgentStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.hnz.luck5.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 代理统计")
@RestController
@RequestMapping("/system/agent-statistics")
@RequiredArgsConstructor
public class AgentStatisticsController {

    private final AgentStatisticsService statisticsService;

    @GetMapping("/summary")
    @Operation(summary = "查看代理及金币统计")
    @PreAuthorize("@ss.hasAnyRoles('super_admin','lottery_agent')")
    public CommonResult<AgentStatisticsService.Summary> summary() {
        return success(statisticsService.summary());
    }

    @GetMapping("/agents")
    @Operation(summary = "管理员查看代理余额列表")
    @PreAuthorize("@ss.hasRole('super_admin')")
    public CommonResult<PageResult<AgentStatisticsService.AgentRow>> agents(@Valid PageParam page) {
        return success(statisticsService.agents(page));
    }

    @GetMapping("/ledger")
    @Operation(summary = "查看金币充值或消耗流水")
    @PreAuthorize("@ss.hasAnyRoles('super_admin','lottery_agent')")
    public CommonResult<PageResult<AgentStatisticsService.LedgerRow>> ledger(
            @Valid PageParam page, @RequestParam("type") String type) {
        return success(statisticsService.ledger(page, type));
    }
}
