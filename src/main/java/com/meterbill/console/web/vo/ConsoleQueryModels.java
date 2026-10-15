package com.meterbill.console.web.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ConsoleQueryModels {

    private ConsoleQueryModels() {
    }

    public record DashboardOverview(
            String currency,
            long receivableMinor,
            long paidMinor,
            long outstandingMinor,
            long unknownPayments,
            long failedPayments,
            long failedUsageEvents,
            long openDifferences,
            long pendingInvoices,
            List<UsagePoint> usageTrend) {
    }

    public record UsagePoint(LocalDate date, long quantity, long eventCount) {
    }

    public record CustomerRow(
            Long tenantId,
            String tenantCode,
            String tenantName,
            String status,
            long memberCount,
            long activeSubscriptionCount,
            long invoiceCount,
            long outstandingMinor,
            String currency,
            Instant createdAt) {
    }

    public record InvoiceRow(
            Long id,
            Long subscriptionId,
            String tenantName,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            long totalAmountMinor,
            long paidAmountMinor,
            String status,
            Instant issuedAt) {
    }

    public record PaymentRow(
            Long id,
            Long invoiceId,
            String externalReference,
            String channelCode,
            String currency,
            long amountMinor,
            String status,
            Instant createdAt,
            Instant completedAt) {
    }

    public record ChannelFact(
            String channelCode,
            String externalReference,
            String transactionType,
            String currency,
            long amountMinor,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TimelineEvent(
            String type,
            String title,
            String description,
            String status,
            Instant occurredAt) {
    }

    public record PaymentInvestigation(
            PaymentRow payment,
            ChannelFact channel,
            List<TimelineEvent> timeline,
            List<RefundRow> refunds,
            List<ReconciliationRow> differences,
            List<OperationTask> tasks) {
    }

    public record RefundRow(
            Long id,
            Long paymentId,
            String externalReference,
            String currency,
            long amountMinor,
            String status,
            Instant createdAt,
            Instant completedAt) {
    }

    public record SubscriptionRow(
            Long id,
            Long planId,
            String planCode,
            String planName,
            String status,
            String currency,
            Long baseAmountMinor,
            Instant periodStart,
            Instant periodEnd) {
    }

    public record AvailablePlan(Long id, String productName, String planCode, String planName) { }

    public record UsageEventRow(
            Long id,
            Long subscriptionId,
            String sourceId,
            String eventId,
            String meterCode,
            String modelCode,
            long quantity,
            String status,
            int deliveryCount,
            String lastError,
            Instant occurredAt,
            Instant receivedAt,
            Instant processedAt) {
    }

    public record OperationTask(
            Long id,
            String taskType,
            String referenceType,
            String referenceId,
            String status,
            int attemptCount,
            String error,
            Instant nextRetryAt,
            Instant createdAt) {
    }

    public record MemberRow(
            Long memberId,
            Long userId,
            String email,
            String displayName,
            String status,
            List<String> roles,
            Instant joinedAt) {
    }

    public record ReconciliationRow(
            Long id,
            String differenceType,
            String referenceType,
            String referenceId,
            String currency,
            Long expectedAmountMinor,
            Long actualAmountMinor,
            String expectedStatus,
            String actualStatus,
            String status,
            String evidence,
            Instant firstSeenAt,
            Instant lastSeenAt) {
    }

    public record LedgerEntryRow(
            Long transactionId,
            String businessType,
            Long businessId,
            String postingAction,
            String currency,
            String description,
            Long entryId,
            String accountCode,
            String accountType,
            String direction,
            long amountMinor,
            Instant createdAt) {
    }
}
