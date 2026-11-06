package com.meterbill.payment.web.vo;

import java.time.Instant;

public record PaymentTransactionVO(Long id, Long accountId, Long paymentId, Long refundId,
                                   int sequenceNo, String transactionType, String status,
                                   String externalReference, String currency,
                                   long amountMinor, Instant effectiveAt) {
}
