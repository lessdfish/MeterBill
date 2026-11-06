package com.meterbill.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@ConditionalOnProperty(prefix = "meterbill.messaging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RabbitTopologyConfig {

    private final String queueType;

    RabbitTopologyConfig(@Value("${meterbill.messaging.queue-type:classic}") String queueType) {
        if (!"classic".equalsIgnoreCase(queueType) && !"quorum".equalsIgnoreCase(queueType)) {
            throw new IllegalArgumentException("Unsupported RabbitMQ queue type: " + queueType);
        }
        this.queueType = queueType;
    }

    @Bean
    DirectExchange usageExchange() {
        return new DirectExchange(MessagingTopology.USAGE_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange retryExchange() {
        return new DirectExchange(MessagingTopology.RETRY_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange failedExchange() {
        return new DirectExchange(MessagingTopology.FAILED_EXCHANGE, true, false);
    }

    @Bean
    Queue usageQueue() {
        return queue(MessagingTopology.USAGE_QUEUE).build();
    }

    @Bean
    Queue retryQueue(@Value("${meterbill.messaging.retry-delay-ms:5000}") int delayMs) {
        return queue(MessagingTopology.RETRY_QUEUE)
                .ttl(delayMs)
                .deadLetterExchange(MessagingTopology.USAGE_EXCHANGE)
                .deadLetterRoutingKey(MessagingTopology.USAGE_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue failedQueue() {
        return queue(MessagingTopology.FAILED_QUEUE).build();
    }

    private QueueBuilder queue(String name) {
        QueueBuilder builder = QueueBuilder.durable(name);
        return "quorum".equalsIgnoreCase(queueType) ? builder.quorum() : builder;
    }

    @Bean
    Binding usageBinding(@Qualifier("usageQueue") Queue usageQueue,
                         @Qualifier("usageExchange") DirectExchange usageExchange) {
        return BindingBuilder.bind(usageQueue).to(usageExchange)
                .with(MessagingTopology.USAGE_ROUTING_KEY);
    }

    @Bean
    Binding retryBinding(@Qualifier("retryQueue") Queue retryQueue,
                         @Qualifier("retryExchange") DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue).to(retryExchange)
                .with(MessagingTopology.RETRY_ROUTING_KEY);
    }

    @Bean
    Binding failedBinding(@Qualifier("failedQueue") Queue failedQueue,
                          @Qualifier("failedExchange") DirectExchange failedExchange) {
        return BindingBuilder.bind(failedQueue).to(failedExchange)
                .with(MessagingTopology.FAILED_ROUTING_KEY);
    }
}
