package com.meterbill.payment.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulatedChannelService {
    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    /** Simulates a channel observation only: deliberately does not deliver a callback. */
    @Transactional
    public void observe(Long tenant, String reference, String type, String currency, long amount, String status, Long actor) {
        if (!List.of("CHARGE","REFUND").contains(type) || !List.of("PENDING","UNKNOWN","SUCCEEDED","FAILED").contains(status)
                || !"CNY".equals(currency) || amount <= 0 || reference == null || reference.isBlank() || reference.length()>64)
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "渠道观察值不合法");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM mb_tenant WHERE id=?", Integer.class, tenant) != 1)
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        // Unique external reference makes the simulated channel independent from order existence.
        jdbc.update("INSERT IGNORE INTO mb_channel_transaction(tenant_id,external_reference,transaction_type,currency,amount_minor,status) VALUES (?,?,?,?,?,?)",
                tenant,reference,type,currency,amount,status);
        var old = jdbc.query("SELECT tenant_id,transaction_type,currency,amount_minor,status FROM mb_channel_transaction WHERE channel_code='SIMULATED' AND external_reference=? FOR UPDATE",
                (rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5)},reference).getFirst();
        if (!tenant.equals(old[0]) || !type.equals(old[1]) || !currency.equals(old[2]) || amount!=(Long)old[3]
                || (List.of("SUCCEEDED","FAILED","REVERSED").contains(old[4]) && !status.equals(old[4])))
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "不能改写渠道事实或终态");
        jdbc.update("UPDATE mb_channel_transaction SET status=? WHERE external_reference=? AND channel_code='SIMULATED'",status,reference);
        audit.record(tenant,actor,"CHANNEL_OBSERVE","CHANNEL_TRANSACTION",reference);
    }
}
