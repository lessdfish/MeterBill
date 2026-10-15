package com.meterbill.identity.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.meterbill.identity.persistence.entity.TenantMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * User membership within tenant Mapper 接口
 * </p>
 *
 * @author MeterBill
 * @since 2026-10-10
 */
@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {

}
