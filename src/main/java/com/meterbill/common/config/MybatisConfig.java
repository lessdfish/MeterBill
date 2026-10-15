package com.meterbill.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ClassName:MybatisConfig
 * Package:com.meterbill.common.config
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 14:22
 * @Version: v1.0
 *
 */
@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mpc(){
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);

        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
