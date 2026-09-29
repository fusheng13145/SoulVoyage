package com.soulvoyage;

import com.soulvoyage.admin.AdminMetricsController;
import com.soulvoyage.admin.ContentAdminController;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G8/G9 在"图未启用"一侧的回归：开关关闭时，观测口与对账口都必须<b>如实</b>说明
 * "KG 跑在 kg_node 表本身、不存在派生视图"，而不是空报成功或假装一致。
 *
 * <p>这一侧的诚实性本身就是验收项——M16 的装配约定是"开关关掉时装配图与 M14 及之前逐字一致"，
 * 因此新增的观测/对账能力在关闭态只能在<i>文案</i>上加东西，不能在<i>装配</i>上加东西。
 * 图启用态的行为由 Neo4jKgContractTest 在有真图实例时钉住。
 */
@SpringBootTest
@ActiveProfiles("test")
class M18GraphOpsTest {

    @Autowired AdminMetricsController metrics;
    @Autowired ContentAdminController adminContent;

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "ADMIN");

    private <T> T as(AuthPrincipal p, Supplier<T> call) {
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void runtimeSnapshotReportsDbProviderWhenGraphDisabled() {
        Map<String, Object> data = as(ADMIN, () -> metrics.overview(7)).getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) data.get("runtime");

        assertEquals("kg=db(kg_node)", runtime.get("kg"),
                "图未启用时必须标明 KG 跑在 kg_node 表本身，不能谎称图口径");
        assertNotNull(runtime.get("llmGate"), "既有 llmGate 口径不应被本次改动影响");
    }

    @Test
    void auditEndpointReportsNoDerivedViewWhenGraphDisabled() {
        ApiResponse<Map<String, Object>> resp = as(ADMIN, () -> adminContent.kgGraphAudit());

        assertEquals(Boolean.FALSE, resp.getData().get("available"));
        assertTrue(String.valueOf(resp.getData().get("reason")).contains("未启用"),
                "图未启用时对账须如实说明无派生视图，而不是假装一致");
    }
}
