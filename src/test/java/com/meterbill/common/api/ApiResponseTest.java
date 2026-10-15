package com.meterbill.common.api;

import com.meterbill.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void successShouldKeepData() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertEquals("SUCCESS", response.code());
        assertEquals("OK", response.message());
        assertEquals("hello", response.data());
    }

    @Test
    void failureShouldKeepErrorCode() {
        ApiResponse<Void> response = ApiResponse.failure(
                ErrorCode.BUSINESS_CONFLICT,
                "订阅状态冲突"
        );

        assertEquals("BUSINESS_CONFLICT", response.code());
        assertEquals("订阅状态冲突", response.message());
        assertNull(response.data());
    }
}