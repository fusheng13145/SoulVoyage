package com.soulvoyage.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ApiResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 转介资源（手册 §6.5 / §7.3）：公开、不加密（本身即面向危急时刻的求助信息，无隐私）。
 * 危机模式前端强制展示卡片时读取此处；SecurityConfig 已放行该路径。
 */
@RestController
@RequestMapping("/api/v1/risk")
@RequiredArgsConstructor
public class RiskController {

    private final ObjectMapper mapper;
    private JsonNode referral;

    @PostConstruct
    void load() throws Exception {
        String json = StreamUtils.copyToString(
                new ClassPathResource("risk/referral.json").getInputStream(), StandardCharsets.UTF_8);
        referral = mapper.readTree(json);
    }

    @GetMapping("/resources")
    public ApiResponse<JsonNode> resources() {
        return ApiResponse.ok(referral);
    }
}
