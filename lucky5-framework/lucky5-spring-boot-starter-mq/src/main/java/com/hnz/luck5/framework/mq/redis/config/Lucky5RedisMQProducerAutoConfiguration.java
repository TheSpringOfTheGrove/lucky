package com.hnz.luck5.framework.mq.redis.config;

import com.hnz.luck5.framework.mq.redis.core.RedisMQTemplate;
import com.hnz.luck5.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import com.hnz.luck5.framework.redis.config.Lucky5RedisAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Redis 消息队列 Producer 配置类
 *
 * @author 芋道源码
 */
@Slf4j
@AutoConfiguration(after = Lucky5RedisAutoConfiguration.class)
public class Lucky5RedisMQProducerAutoConfiguration {

    @Bean
    public RedisMQTemplate redisMQTemplate(StringRedisTemplate redisTemplate,
                                           List<RedisMessageInterceptor> interceptors) {
        RedisMQTemplate redisMQTemplate = new RedisMQTemplate(redisTemplate);
        // 添加拦截器
        interceptors.forEach(redisMQTemplate::addInterceptor);
        return redisMQTemplate;
    }

}
