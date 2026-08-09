package com.hnz.luck5.framework.idempotent.config;

import com.hnz.luck5.framework.idempotent.core.aop.IdempotentAspect;
import com.hnz.luck5.framework.idempotent.core.keyresolver.impl.DefaultIdempotentKeyResolver;
import com.hnz.luck5.framework.idempotent.core.keyresolver.impl.ExpressionIdempotentKeyResolver;
import com.hnz.luck5.framework.idempotent.core.keyresolver.IdempotentKeyResolver;
import com.hnz.luck5.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import com.hnz.luck5.framework.idempotent.core.redis.IdempotentRedisDAO;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import com.hnz.luck5.framework.redis.config.Lucky5RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@AutoConfiguration(after = Lucky5RedisAutoConfiguration.class)
public class Lucky5IdempotentConfiguration {

    @Bean
    public IdempotentAspect idempotentAspect(List<IdempotentKeyResolver> keyResolvers, IdempotentRedisDAO idempotentRedisDAO) {
        return new IdempotentAspect(keyResolvers, idempotentRedisDAO);
    }

    @Bean
    public IdempotentRedisDAO idempotentRedisDAO(StringRedisTemplate stringRedisTemplate) {
        return new IdempotentRedisDAO(stringRedisTemplate);
    }

    // ========== 各种 IdempotentKeyResolver Bean ==========

    @Bean
    public DefaultIdempotentKeyResolver defaultIdempotentKeyResolver() {
        return new DefaultIdempotentKeyResolver();
    }

    @Bean
    public UserIdempotentKeyResolver userIdempotentKeyResolver() {
        return new UserIdempotentKeyResolver();
    }

    @Bean
    public ExpressionIdempotentKeyResolver expressionIdempotentKeyResolver() {
        return new ExpressionIdempotentKeyResolver();
    }

}
