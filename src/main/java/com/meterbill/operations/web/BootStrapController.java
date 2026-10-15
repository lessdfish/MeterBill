package com.meterbill.operations.web;

import com.meterbill.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ClassName:BootStrapController
 * Package:com.meterbill.operations.web
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 13:06
 * @Version: v1.0
 *
 */
@Tag(name = "系统",description = "系统启动与连通性检查接口")
@RestController
@RequestMapping("/api/v1/system")
public class BootStrapController {

    @Operation(summary = "检查接口连通性")
    @GetMapping("/ping")
    public ApiResponse<Map<String,String>> ping(){
        return ApiResponse.success(Map.of(
                "service","MeterBill",
                "status","READY"
        ));
    }
}
