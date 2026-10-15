package com.meterbill.messaging;

import com.meterbill.metering.service.UsageEventProcessor;
import com.meterbill.observability.BusinessMetrics;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "meterbill.messaging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class UsageEventListener {

    private static final int MAX_DELIVERIES = 3;
    private final UsageEventProcessor processor;
    private final ConfirmedRabbitPublisher publisher;
    private final BusinessMetrics metrics;

    @Autowired
    public UsageEventListener(UsageEventProcessor processor, ConfirmedRabbitPublisher publisher,
                              BusinessMetrics metrics) {
        this.processor = processor;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public UsageEventListener(UsageEventProcessor processor, ConfirmedRabbitPublisher publisher) {
        this(processor, publisher, null);
    }

    @RabbitListener(queues = MessagingTopology.USAGE_QUEUE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long eventId = parseEventId(message);
        try {
            processor.process(eventId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            int priorRetries = retryCount(message);
            int nextRetry = priorRetries + 1;
            boolean finalFailure = nextRetry >= MAX_DELIVERIES;
            if (metrics != null) {
                metrics.recordMqFailure("consumer", finalFailure);
            }
            String exchange = finalFailure
                    ? MessagingTopology.FAILED_EXCHANGE : MessagingTopology.RETRY_EXCHANGE;
            String routingKey = finalFailure
                    ? MessagingTopology.FAILED_ROUTING_KEY : MessagingTopology.RETRY_ROUTING_KEY;
            try {
                publisher.publish(exchange, routingKey,
                        new String(message.getBody(), StandardCharsets.UTF_8), nextRetry);
                if (eventId != null) {
                    processor.recordFailure(eventId, finalFailure, ex.getMessage());
                }
                channel.basicAck(deliveryTag, false);
            } catch (RuntimeException publishFailure) {
                if (eventId != null) {
                    try {
                        processor.recordFailure(eventId, false,
                                "Retry publish failed: " + publishFailure.getMessage());
                    } catch (RuntimeException dbFailure) {
                        publishFailure.addSuppressed(dbFailure);
                    }
                }
                log.error("Could not route failed usage message; requeueing", publishFailure);
                channel.basicNack(deliveryTag, false, true);
            }
            log.warn("Usage event {} failed delivery {} of {}",
                    eventId, nextRetry, MAX_DELIVERIES, ex);
        }
    }

    private Long parseEventId(Message message) {
        try {
            return Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders()
                .get(MessagingTopology.RETRY_HEADER);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
