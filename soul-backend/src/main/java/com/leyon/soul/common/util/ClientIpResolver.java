package com.leyon.soul.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 客户端 IP 解析（2026-11-20 安全审查修复）。
 *
 * <p>问题：原实现取 {@code X-Forwarded-For} 的<b>第一段</b>。而 nginx 的 {@code $proxy_add_x_forwarded_for}
 * 会把客户端自带的 XFF 放在最前、真实 IP 追加在后，于是"第一段"正好是攻击者可任意伪造的值——
 * 登录失败计数键含 IP，换一个 XFF 就换一个计数器，5 次锁定形同虚设（已用真实请求复现）。
 *
 * <p>现在的规则：
 * <ol>
 *   <li>只有请求来自本机/内网（即确实是 nginx/容器网络转发过来的）才采信代理头；</li>
 *   <li>优先 {@code X-Real-IP}（nginx 用 {@code $remote_addr} 覆盖写入，客户端无法伪造）；</li>
 *   <li>其次取 XFF 的<b>最后一段</b>（代理追加的真实地址）；</li>
 *   <li>直连公网或头里不是合法 IP 字面量时，一律回 {@code getRemoteAddr()}。</li>
 * </ol>
 * 不用自研 IP 解析库：只做字面量校验与内网前缀判断，避免 DNS 反查与复杂 CIDR 带来的新风险。
 */
@Component
public class ClientIpResolver {

    /** 允许出现在头里的 IP 字面量：仅十六进制/冒号/点，长度 7–45（覆盖 IPv6） */
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{7,45}$");

    private final boolean trustProxyHeaders;

    public ClientIpResolver(@Value("${soul.security.trust-proxy-headers:true}") boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public String resolve(HttpServletRequest req) {
        if (req == null) return "unknown";
        String remote = req.getRemoteAddr();
        if (!trustProxyHeaders || !isLocalOrPrivate(remote)) {
            // 直连对外暴露时，任何 X-Forwarded-* 都可能是客户端自己写的，不能用于风控
            return remote == null ? "unknown" : remote;
        }
        String realIp = req.getHeader("X-Real-IP");
        if (looksLikeIp(realIp)) return realIp.trim();

        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (looksLikeIp(last)) return last;
        }
        return remote;
    }

    /** 环回 / 私有网段：nginx 与 app 同机或同一 docker 网络时的典型来源 */
    static boolean isLocalOrPrivate(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        // 172.16.0.0/12
        if (ip.startsWith("172.")) {
            int second;
            try {
                second = Integer.parseInt(ip.split("\\.")[1]);
            } catch (Exception e) {
                return false;
            }
            return second >= 16 && second <= 31;
        }
        // IPv6 唯一本地地址 fc00::/7 / 链路本地 fe80::/10
        String lower = ip.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe8")
                || lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb");
    }

    static boolean looksLikeIp(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty() || v.length() > 45 || !IP_LITERAL.matcher(v).matches()) return false;
        if (v.indexOf(':') >= 0) {
            // 冒号形态只可能是 IPv6 字面量：正则已排除字母（a-f 除外）与主机名字符，
            // 因此用 InetAddress 解析不会触发 DNS（纯字面量解析，非法即抛）。
            try {
                return java.net.InetAddress.getByName(v) instanceof java.net.Inet6Address;
            } catch (Exception e) {
                return false;
            }
        }
        String[] p = v.split("\\.");
        if (p.length != 4) return false;
        for (String seg : p) {
            if (seg.isEmpty() || seg.length() > 3) return false;
            int n;
            try {
                n = Integer.parseInt(seg);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n < 0 || n > 255) return false;
        }
        return true;
    }
}
