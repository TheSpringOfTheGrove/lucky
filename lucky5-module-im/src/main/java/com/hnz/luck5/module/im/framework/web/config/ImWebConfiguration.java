package com.hnz.luck5.module.im.framework.web.config;

import com.hnz.luck5.framework.swagger.config.Lucky5SwaggerAutoConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * im 模块的 web 组件的 Configuration
 */
@Configuration(proxyBeanMethods = false)
public class ImWebConfiguration {

    /**
     * im 模块的 API 分组
     */
    @Bean
    public GroupedOpenApi imGroupedOpenApi() {
        return Lucky5SwaggerAutoConfiguration.buildGroupedOpenApi("im");
    }

}
