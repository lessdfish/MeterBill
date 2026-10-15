
package com.meterbill.product.web.vo;

import com.meterbill.product.persistence.entity.UsageMeter;

public record UsageMeterVO(
        Long id,
        Long productId,
        String meterCode,
        String name,
        String unit
) {
    public static UsageMeterVO from(UsageMeter meter) {
        return new UsageMeterVO(
                meter.getId(),
                meter.getProductId(),
                meter.getMeterCode(),
                meter.getName(),
                meter.getUnit()
        );
    }
}
