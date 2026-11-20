package com.leyon.soul.auth;

/**
 * Redis 降级时的"能不能继续放行"判定（2026-11-20 审查 04 项：吊销读降级改 fail-closed 的折中实现）。
 *
 * <p>全站 fail-closed 会把"Redis 抖一下"放大成"全站 401"，代价过大；全站降级又会让被吊销的令牌
 * 在故障窗口内继续可用。折中：**读接口继续降级**（鉴权不因缓存不可用整体失败），
 * **写操作与管理端一律 fail-closed**——写操作才是"用别人的旧令牌造成实际副作用"的高危面。
 *
 * <p>抽成纯函数是为了能单测：过滤器的正确性不该只靠"跑一次 Redis 宕机"来证明。
 */
public final class DegradationPolicy {

    private DegradationPolicy() {}

    /** 是否要求"吊销状态必须可信"才放行 */
    public static boolean requiresTrustedRevocation(String method, String uri) {
        if (uri == null) return false;
        // 登录/注册/刷新本身就要在 Redis 故障下给出明确语义（写侧 writeRequired 已 503），不在这里重复拦
        if (uri.startsWith("/api/v1/auth/")) return false;
        // 健康检查/SSE 续跳/错误派发不该被缓存拖死
        if (uri.equals("/actuator/health") || uri.equals("/error")) return false;
        String m = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        boolean readOnly = m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
        if (!readOnly) return true;              // 所有写操作
        return uri.startsWith("/api/v1/admin/"); // 管理端读：越权看到别人数据的代价高于可用性
    }
}
