package com.meterbill.identity.security;

import com.meterbill.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ClassName:SecurityErrorWriter
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 17:57
 * @Version: v1.0
 *
 */
public class SecurityErrorWriter {
    public SecurityErrorWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException{

        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code":"%s","message":"%s","data":null}
                """.formatted(code.getCode(),code.getMessage()));
    }
}
