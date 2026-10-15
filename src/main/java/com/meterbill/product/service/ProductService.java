package com.meterbill.product.service;

import com.meterbill.common.api.PageResponse;
import com.meterbill.product.web.dto.CreateProduct;
import com.meterbill.product.web.vo.ProductVO;

/**
 * ClassName:ProductService
 * Package:com.meterbill.product.service
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 13:31
 * @Version: v1.0
 *
 */
public interface ProductService {

    Long create(Long userId, CreateProduct create);

    PageResponse<ProductVO> list(Long userId, long page, long size);

    ProductVO publish(Long userId, Long productId);
}
