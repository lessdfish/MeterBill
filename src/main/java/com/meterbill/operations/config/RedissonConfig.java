package com.meterbill.operations.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnProperty(prefix = "meterbill.redisson", name = "enabled", havingValue = "true")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(
            @Value("${meterbill.redisson.mode:single}") String mode,
            @Value("${meterbill.redisson.host:127.0.0.1}") String host,
            @Value("${meterbill.redisson.port:16379}") int port,
            @Value("${meterbill.redisson.password:}") String password,
            @Value("${meterbill.redisson.sentinel.master:}") String sentinelMaster,
            @Value("${meterbill.redisson.sentinel.nodes:}") String sentinelNodes) {
        Config config = new Config();
        if ("sentinel".equalsIgnoreCase(mode)) {
            String[] nodes = Arrays.stream(sentinelNodes.split(","))
                    .map(String::trim)
                    .filter(node -> !node.isEmpty())
                    .map(node -> node.contains("://") ? node : "redis://" + node)
                    .toArray(String[]::new);
            if (sentinelMaster.isBlank() || nodes.length < 3) {
                throw new IllegalArgumentException(
                        "Sentinel mode requires a master name and at least three Sentinel nodes");
            }
            config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(nodes)
                    .setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(1)
                    .setMasterConnectionMinimumIdleSize(2).setMasterConnectionPoolSize(8)
                    .setSlaveConnectionMinimumIdleSize(2).setSlaveConnectionPoolSize(8)
                    .setSubscriptionConnectionMinimumIdleSize(1)
                    .setSubscriptionConnectionPoolSize(2);
        } else if ("single".equalsIgnoreCase(mode)) {
            config.useSingleServer().setAddress("redis://" + host + ":" + port)
                    .setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(1)
                    .setConnectionMinimumIdleSize(2).setConnectionPoolSize(8)
                    .setSubscriptionConnectionMinimumIdleSize(1)
                    .setSubscriptionConnectionPoolSize(2);
        } else {
            throw new IllegalArgumentException("Unsupported Redisson mode: " + mode);
        }
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        return Redisson.create(config);
    }
}
