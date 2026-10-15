package com.meterbill.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(prefix = "meterbill.messaging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    public ConfirmedRabbitPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String exchange, String routingKey, String body, int retryCount) {
        publishAsync(exchange, routingKey, body, retryCount).join();
    }

    public CompletableFuture<Void> publishAsync(String exchange, String routingKey, String body, int retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(MessagingTopology.RETRY_HEADER, retryCount);
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            return correlation.getFuture().orTimeout(10, TimeUnit.SECONDS).thenApply(confirm -> {
                if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ rejected publication: " + confirm.getReason());
                if (correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ could not route the published message");
                return null;
            });
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(new IllegalStateException("RabbitMQ publication was not confirmed", ex));
        }
    }
}
