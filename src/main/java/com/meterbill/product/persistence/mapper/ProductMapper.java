package com.meterbill.product.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.meterbill.product.persistence.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author MeterBill
 * @since 2026-10-11
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

}
