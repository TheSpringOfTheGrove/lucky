package com.hnz.luck5.module.infra.framework.web.config;

import com.hnz.luck5.framework.swagger.config.Lucky5SwaggerAutoConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * infra 模块的 web 组件的 Configuration
 *
 * @author 芋道源码
 */
@Configuration(proxyBeanMethods = false)
public class InfraWebConfiguration {

    /**
     * infra 模块的 API 分组
     */
    @Bean
    public GroupedOpenApi infraGroupedOpenApi() {
        return Lucky5SwaggerAutoConfiguration.buildGroupedOpenApi("infra");
    }

}
