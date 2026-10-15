
package com.meterbill.product.web.vo;

import com.meterbill.product.persistence.entity.Price;

public record PriceVO(
        Long id,
        Long planId,
        String currency,
        String billingPeriod
) {
    public static PriceVO from(Price price) {
        return new PriceVO(
                price.getId(),
                price.getPlanId(),
                price.getCurrency(),
                price.getBillingPeriod()
        );
    }
}
