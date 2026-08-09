package com.hnz.luck5.module.pay.controller.app.wallet;

import cn.hutool.core.collection.CollUtil;
import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.framework.common.pojo.PageParam;
import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.pay.controller.app.wallet.vo.recharge.AppPayWalletRechargeCreateReqVO;
import com.hnz.luck5.module.pay.controller.app.wallet.vo.recharge.AppPayWalletRechargeCreateRespVO;
import com.hnz.luck5.module.pay.controller.app.wallet.vo.recharge.AppPayWalletRechargeRespVO;
import com.hnz.luck5.module.pay.convert.wallet.PayWalletConvert;
import com.hnz.luck5.module.pay.convert.wallet.PayWalletRechargeConvert;
import com.hnz.luck5.module.pay.dal.dataobject.order.PayOrderDO;
import com.hnz.luck5.module.pay.dal.dataobject.wallet.PayWalletRechargeDO;
import com.hnz.luck5.module.pay.service.order.PayOrderService;
import com.hnz.luck5.module.pay.service.wallet.PayWalletRechargeService;
import com.google.common.collect.Lists;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.hnz.luck5.framework.common.pojo.CommonResult.success;
import static com.hnz.luck5.framework.common.util.collection.CollectionUtils.convertList;
import static com.hnz.luck5.framework.common.util.servlet.ServletUtils.getClientIP;
import static com.hnz.luck5.framework.web.core.util.WebFrameworkUtils.getLoginUserId;
import static com.hnz.luck5.framework.web.core.util.WebFrameworkUtils.getLoginUserType;

@Tag(name = "用户 APP - 钱包充值")
@RestController
@RequestMapping("/pay/wallet-recharge")
@Validated
@Slf4j
public class AppPayWalletRechargeController {

    @Resource
    private PayWalletRechargeService walletRechargeService;
    @Resource
    private PayOrderService payOrderService;

    @PostMapping("/create")
    @Operation(summary = "创建钱包充值记录（发起充值）")
    public CommonResult<AppPayWalletRechargeCreateRespVO> createWalletRecharge(
            @Valid @RequestBody  AppPayWalletRechargeCreateReqVO reqVO) {
        PayWalletRechargeDO walletRecharge = walletRechargeService.createWalletRecharge(
                getLoginUserId(), getLoginUserType(), getClientIP(), reqVO);
        return success(PayWalletRechargeConvert.INSTANCE.convert(walletRecharge));
    }

    @GetMapping("/page")
    @Operation(summary = "获得钱包充值记录分页")
    public CommonResult<PageResult<AppPayWalletRechargeRespVO>> getWalletRechargePage(@Valid PageParam pageReqVO) {
        PageResult<PayWalletRechargeDO> pageResult = walletRechargeService.getWalletRechargePackagePage(
                getLoginUserId(), UserTypeEnum.MEMBER.getValue(), pageReqVO, true);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(PageResult.empty(pageResult.getTotal()));
        }
        // 拼接数据
        List<PayOrderDO> payOrderList = payOrderService.getOrderList(
                convertList(pageResult.getList(), PayWalletRechargeDO::getPayOrderId));
        return success(PayWalletRechargeConvert.INSTANCE.convertPage(pageResult, payOrderList));
    }

}
