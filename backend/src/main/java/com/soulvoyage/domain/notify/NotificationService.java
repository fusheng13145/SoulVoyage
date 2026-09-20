package com.soulvoyage.domain.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * G5 通知中心服务：所有站内通知唯一出口。
 * 文案模板集中在 resources/notify/notify_templates.json（心理顾问审阅面收窄到一个文件）；
 * 频率护栏：单人每日 ≤2 条（提醒类让路，不骚扰）；dedupKey 幂等（定时扫描重跑不重复）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int DAILY_CAP = 2;

    private final NotificationRepository repo;
    private final ObjectMapper mapper;

    private Map<String, Map<String, String>> templates = Map.of();

    @PostConstruct
    void loadTemplates() {
        try {
            templates = mapper.readValue(new ClassPathResource("notify/notify_templates.json").getInputStream(),
                    new TypeReference<>() {});
        } catch (Exception e) {
            log.error("notify templates load failed", e);
        }
    }

    /** 返回是否实际落库（被去重/护栏拦下返回 false） */
    public boolean push(long userId, String kind, String dedupKey, String templateKey,
                        Map<String, String> vars, String link) {
        try {
            if (dedupKey != null && !dedupKey.isBlank()
                    && repo.findByUserIdAndDedupKey(userId, dedupKey).isPresent()) return false;
            if (repo.countByUserIdAndCreatedAtGreaterThanEqual(userId,
                    Instant.now().minusSeconds(24 * 3600L)) >= DAILY_CAP) {
                log.debug("notify daily cap reached user={} kind={}", userId, kind);
                return false;
            }
            Map<String, String> tpl = templates.get(templateKey);
            if (tpl == null) {
                log.warn("notify template missing: {}", templateKey);
                return false;
            }
            NotificationEntity n = new NotificationEntity();
            n.setUserId(userId);
            n.setKind(kind);
            n.setDedupKey(dedupKey == null || dedupKey.isBlank() ? null : dedupKey);
            n.setTitle(render(tpl.get("title"), vars));
            n.setBody(render(tpl.get("body"), vars));
            n.setLink(link);
            repo.save(n);
            return true;
        } catch (Exception e) {
            log.warn("notify push failed user={} key={}: {}", userId, templateKey, e.toString());
            return false;
        }
    }

    private String render(String s, Map<String, String> vars) {
        String out = s;
        for (var e : vars.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        return out;
    }

    // ---------------- 通知中心读取侧（顶栏铃铛） ----------------

    public Map<String, Object> list(long userId, int page, int size) {
        var result = repo.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))));
        var items = result.getContent().stream().map(n -> {
            Map<String, Object> m = new java.util.HashMap<String, Object>();
            m.put("id", n.getId());
            m.put("kind", n.getKind());
            m.put("title", n.getTitle());
            m.put("body", n.getBody());
            m.put("link", n.getLink());
            m.put("readAt", n.getReadAt() == null ? null : n.getReadAt().toString());
            m.put("createdAt", n.getCreatedAt().toString());
            return m;
        }).toList();
        return Map.of("items", items, "total", result.getTotalElements(),
                "unreadCount", repo.countByUserIdAndReadAtIsNull(userId));
    }

    /** 属主断言：别人的通知与不存在的通知同样 404，不靠"静默成功"掩盖越权（M10 IDOR 矩阵） */
    @org.springframework.transaction.annotation.Transactional
    public void markRead(long userId, long id) {
        NotificationEntity n = repo.findById(id)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new com.soulvoyage.common.exception.BizException(
                        com.soulvoyage.common.api.ErrorCode.NOT_FOUND));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repo.save(n);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void markAllRead(long userId) {
        for (NotificationEntity n : repo.findByUserIdAndReadAtIsNull(userId)) {
            n.setReadAt(Instant.now());
            repo.save(n);
        }
    }
}
