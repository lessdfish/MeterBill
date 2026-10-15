package com.meterbill.metering;

import com.meterbill.messaging.UsageEventListener;
import com.meterbill.metering.service.UsageEventProcessor;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

/** Forked test-only worker: terminate the real listener exactly at its first ACK. */
public class CommitBeforeAckWorker {
    public static void main(String[] args) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(WorkerConfig.class)) {
            var factory = new ConnectionFactory();
            factory.setHost(System.getenv("ACK_RABBIT_HOST"));
            factory.setPort(Integer.parseInt(System.getenv("ACK_RABBIT_PORT")));
            factory.setUsername("test");
            factory.setPassword("test");
            try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
                var delivery = channel.basicGet(args[0], false);
                if (delivery == null) throw new IllegalStateException("No test delivery");
                var properties = new MessageProperties();
                properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                Channel crashAtAck = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                        new Class<?>[]{Channel.class}, (proxy, method, values) -> {
                            if ("basicAck".equals(method.getName())) {
                                System.out.println("COMMITTED_BEFORE_ACK");
                                System.out.flush();
                                Runtime.getRuntime().halt(73);
                            }
                            return method.invoke(channel, values);
                        });
                new UsageEventListener(context.getBean(UsageEventProcessor.class), null)
                        .consume(new Message(delivery.getBody(), properties), crashAtAck);
                throw new IllegalStateException("ACK boundary was not reached");
            }
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class WorkerConfig {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("ACK_JDBC_URL"), "test", "test");
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean UsageEventProcessor processor(JdbcTemplate jdbc) { return new UsageEventProcessor(jdbc); }
    }
}
