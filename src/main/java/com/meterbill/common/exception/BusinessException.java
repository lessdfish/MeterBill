package com.meterbill.common.exception;

import lombok.Getter;

import java.util.Objects;

/**
 * ClassName:BusinessException
 * Package:com.meterbill.common.exception
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 14:08
 * @Version: v1.0
 *
 */
@Getter
public class BusinessException extends RuntimeException{
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode){
        this(errorCode,errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode,String message){
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }
}
