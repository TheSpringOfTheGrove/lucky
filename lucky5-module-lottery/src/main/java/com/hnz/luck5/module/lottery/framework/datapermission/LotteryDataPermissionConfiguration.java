package com.hnz.luck5.module.lottery.framework.datapermission;

import com.hnz.luck5.framework.common.biz.system.permission.PermissionCommonApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LotteryDataPermissionConfiguration {

    @Bean
    public LotteryUserDataPermissionRule lotteryUserDataPermissionRule(PermissionCommonApi permissionApi) {
        return new LotteryUserDataPermissionRule(permissionApi);
    }

}
