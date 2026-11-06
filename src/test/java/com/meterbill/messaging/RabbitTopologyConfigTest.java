package com.meterbill.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitTopologyConfigTest {

    @Test
    void createsQuorumQueuesWhenConfigured() {
        var queue = new RabbitTopologyConfig("quorum").usageQueue();

        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    void keepsClassicQueuesAsTheDefaultCompatibleMode() {
        var queue = new RabbitTopologyConfig("classic").usageQueue();

        assertThat(queue.getArguments()).doesNotContainKey("x-queue-type");
    }

    @Test
    void rejectsUnsupportedQueueTypes() {
        assertThatThrownBy(() -> new RabbitTopologyConfig("stream"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported RabbitMQ queue type");
    }
}
