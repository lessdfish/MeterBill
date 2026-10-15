package com.meterbill.operations.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "meterbill.redisson", name = "enabled", havingValue = "true")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(
            @Value("${meterbill.redisson.host:127.0.0.1}") String host,
            @Value("${meterbill.redisson.port:16379}") int port,
            @Value("${meterbill.redisson.password:}") String password) {
        Config config = new Config();
        var server = config.useSingleServer().setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(1)
                .setConnectionMinimumIdleSize(2).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        return Redisson.create(config);
    }
}
