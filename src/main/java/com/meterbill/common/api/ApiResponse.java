package com.meterbill.common.api;

import com.meterbill.common.exception.ErrorCode;

/**
 * ClassName:ApiResponse
 * Package:com.meterbill.common.api
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 13:36
 * @Version: v1.0
 *
 */
public record ApiResponse<T>(String code,
                             String message,
                             T data){
    public static <T> ApiResponse<T> success(T data){
        return new ApiResponse<>("SUCCESS","OK",data);
    }

    public static ApiResponse<Void> failure(ErrorCode code, String message) {
        return new ApiResponse<>(code.getCode(), message, null);
    }

    public static <T> ApiResponse<T> failure(
            ErrorCode code, String message, T data) {
        return new ApiResponse<>(code.getCode(), message, data);
    }

}
