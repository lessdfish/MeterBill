package com.meterbill.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * ClassName:ErrorCode
 * Package:com.meterbill.common.exception
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 14:07
 * @Version: v1.0
 *
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_ARGUMENT("INVALID_ARGUMENT", "请求参数不合法", HttpStatus.BAD_REQUEST),
    BUSINESS_CONFLICT("BUSINESS_CONFLICT", "业务状态冲突", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    UNAUTHORIZED("UNAUTHORIZED", "未认证或登录已失效", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "账号或密码错误", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "无权限执行此操作", HttpStatus.FORBIDDEN),
    TENANT_REQUIRED("TENANT_REQUIRED", "缺少或无效的租户标识", HttpStatus.BAD_REQUEST),
    TENANT_FORBIDDEN("TENANT_FORBIDDEN", "没有该租户的访问权限", HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
