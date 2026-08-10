package com.hnz.luck5.module.lottery.framework.datapermission;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LotteryDataPermissionConfiguration {

    @Bean
    public LotteryUserDataPermissionRule lotteryUserDataPermissionRule() {
        return new LotteryUserDataPermissionRule();
    }

}
