package com.hnz.luck5.module.pay.framework.web.config;

import com.hnz.luck5.framework.swagger.config.Lucky5SwaggerAutoConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pay 模块的 web 组件的 Configuration
 *
 * @author 芋道源码
 */
@Configuration(proxyBeanMethods = false)
public class PayWebConfiguration {

    /**
     * pay 模块的 API 分组
     */
    @Bean
    public GroupedOpenApi payGroupedOpenApi() {
        return Lucky5SwaggerAutoConfiguration.buildGroupedOpenApi("pay");
    }

}
