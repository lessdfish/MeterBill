package com.meterbill.operations.service;

import com.meterbill.billing.service.InvoiceService;
import com.meterbill.observability.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "meterbill.jobs", name = "enabled", havingValue = "true")
public class InvoiceRecoveryJob {

    private final InvoiceService invoices;
    private final TaskCoordinationService coordination;
    private final BusinessMetrics metrics;

    @Scheduled(fixedDelayString = "${meterbill.jobs.invoice-recovery-interval:60000}")
    public void recoverDueInvoices() {
        for (Long periodId : invoices.findDueUnbilledPeriodIds(100)) {
            coordination.runIfAcquired("invoice-period:" + periodId,
                    () -> {
                        try {
                            invoices.issuePeriod(periodId);
                        } catch (RuntimeException ex) {
                            metrics.recordBillingFailure();
                            throw ex;
                        }
                    });
        }
    }
}
