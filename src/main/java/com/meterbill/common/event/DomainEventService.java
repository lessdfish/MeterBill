package com.meterbill.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DomainEventService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void append(Long tenantId, Long accountId, String aggregateType,
                       Object aggregateId, Long aggregateVersion, String eventType,
                       String eventKey, String correlationId, Map<String, ?> payload) {
        jdbc.update("""
                INSERT IGNORE INTO mb_domain_event
                    (tenant_id, account_id, aggregate_type, aggregate_id, aggregate_version,
                     event_type, event_key, payload_json, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                """, tenantId, accountId, aggregateType, String.valueOf(aggregateId),
                aggregateVersion, eventType, eventKey, json(payload), correlationId,
                Timestamp.from(clock.instant()));
    }

    private String json(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("领域事件载荷无法序列化", ex);
        }
    }
}
