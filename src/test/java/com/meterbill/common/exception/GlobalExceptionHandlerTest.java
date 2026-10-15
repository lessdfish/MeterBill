package com.meterbill.common.exception;

import com.meterbill.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerTest.ProbeController.class
})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidBodyShouldReturn400() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.data.name").exists());
    }

    @Test
    void businessConflictShouldReturn409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
                .andExpect(jsonPath("$.message").value("订阅状态冲突"));
    }

    @Test
    void malformedJsonShouldReturn400() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    void unknownPathShouldReturn404() throws Exception {
        mockMvc.perform(get("/test/not-existing"))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class ProbeController {

        @PostMapping("/test/validation")
        public ApiResponse<Void> validate(@Valid @RequestBody ProbeRequest request) {
            return ApiResponse.success(null);
        }

        @GetMapping("/test/conflict")
        public ApiResponse<Void> conflict() {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "订阅状态冲突"
            );
        }
    }

    record ProbeRequest(
            @NotBlank(message = "name不能为空") String name
    ) {
    }
}