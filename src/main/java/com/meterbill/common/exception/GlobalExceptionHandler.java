package com.meterbill.common.exception;

import com.meterbill.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ClassName:GlobalExceptionHandler
 * Package:com.meterbill.common.exception
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 14:11
 * @Version: v1.0
 *
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex){
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code,ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() == null
                            ? "参数不合法"
                            : fieldError.getDefaultMessage()
            );
        }

        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code, code.getMessage(), errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(
            HandlerMethodValidationException ex) {

        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code, code.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {

        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code, "请求体格式错误"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex) {

        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code, code.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {

        // 保留 Spring MVC 框架异常的 HTTP 状态，例如 404、405。
        if (ex instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();

            String message = switch (status) {
                case 404 -> "请求路径不存在";
                case 405 -> "请求方法不支持";
                default -> status >= 500
                        ? "服务器内部错误"
                        : "请求不合法";
            };

            if (status >= 500) {
                log.error("Unhandled framework exception", ex);
            }

            return ResponseEntity.status(error.getStatusCode())
                    .body(new ApiResponse<>("HTTP_" + status, message, null));
        }

        log.error("Unhandled application exception", ex);

        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code, code.getMessage()));
    }
}
