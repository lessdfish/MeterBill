package com.meterbill.operations.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonConfigTest {

    private final RedissonConfig config = new RedissonConfig();

    @Test
    void sentinelModeRequiresThreeSentinelNodes() {
        assertThatThrownBy(() -> config.redissonClient(
                "sentinel", "127.0.0.1", 6379, "password",
                "meterbill-master", "sentinel1:26379,sentinel2:26379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least three Sentinel nodes");
    }

    @Test
    void rejectsUnsupportedModes() {
        assertThatThrownBy(() -> config.redissonClient(
                "cluster", "127.0.0.1", 6379, "password", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Redisson mode");
    }
}
