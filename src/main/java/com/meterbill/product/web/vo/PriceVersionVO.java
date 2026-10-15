
package com.meterbill.product.web.vo;

import com.meterbill.product.persistence.entity.PriceVersion;

import java.time.Instant;
import java.time.ZoneOffset;

public record PriceVersionVO(
        Long id,
        Long priceId,
        Integer versionNo,
        Long baseAmountMinor,
        String status,
        Instant effectiveAt,
        Integer revision
) {
    public static PriceVersionVO from(PriceVersion value) {
        return new PriceVersionVO(
                value.getId(),
                value.getPriceId(),
                value.getVersionNo(),
                value.getBaseAmountMinor(),
                value.getStatus(),
                value.getEffectiveAt() == null
                        ? null
                        : value.getEffectiveAt()
                          .toInstant(ZoneOffset.UTC),
                value.getRevision()
        );
    }
}
