package com.soulvoyage.domain.diary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.orchestrator.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日记本（下篇·C1）：DIARY_PIPELINE 提交同步落库、列表/详情/编辑/删除、
 * 编辑或删除 → 关联报告置 stale 并提供"按新内容重新分析"（复用流水线）、服务端草稿。
 * 原文一律 ✦ 信封加密；预览/搜索在服务端解密后进行，密文永不出域。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiaryService {

    public static final int MAX_LEN = 5000;
    private static final String DRAFT_KIND = "DIARY";

    private final DiaryRepository diaryRepo;
    private final DraftRepository draftRepo;
    private final ReportRepository reportRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final OrchestratorService orchestrator;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final com.soulvoyage.common.time.BusinessCalendar cal;

    private ZoneId zone() { return cal.zone(); }

    /** POST /tasks(DIARY_PIPELINE) 提交即同步落库；clientReqId 幂等重放（返回同一任务）不重复写 */
    @Transactional
    public void attachTask(long userId, JsonNode payload, TaskInstanceEntity task) {
        if (diaryRepo.findByTaskIdAndUserId(task.getId(), userId).isPresent()) return;
        String text = payload.path("diaryText").asText("");
        if (text.isBlank()) return;
        checkLen(text);
        DiaryEntity d = new DiaryEntity();
        d.setUserId(userId);
        d.setContentEnc(crypto.encryptUserField(userId, text));
        d.setRecordDate(parseDate(payload.path("recordDate").asText(
                payload.path("diaryDate").asText(LocalDate.now(zone()).toString()))));
        Short mood = readMood(payload.path("moodSelfRating"));
        d.setMoodSelfRating(mood);
        d.setTaskId(task.getId());
        diaryRepo.save(d);
    }

    /** 列表（月份分组在前端做）：范围过滤 → 解密 → q 子串过滤 → 分页；只回预览不回原文 */
    public Map<String, Object> list(long userId, LocalDate from, LocalDate to, String q,
                                    int page, int size) {
        LocalDate f = from == null ? LocalDate.of(1970, 1, 1) : from;
        LocalDate t = to == null ? LocalDate.now(zone()) : to;
        List<DiaryEntity> rows = diaryRepo
                .findByUserIdAndDeletedAtIsNullAndRecordDateBetweenOrderByRecordDateDescIdDesc(userId, f, t);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DiaryEntity d : rows) {
            String content;
            try {
                content = crypto.decryptUserField(userId, d.getContentEnc());
            } catch (Exception e) {
                // 单条解密失败（如密钥已销毁）不能让整页 500
                log.warn("diary {} decrypt failed for user {}", d.getId(), userId, e);
                content = "（无法解密）";
            }
            if (q != null && !q.isBlank() && !content.contains(q.trim())) continue;
            items.add(Map.of(
                    "id", d.getId(),
                    "recordDate", d.getRecordDate().toString(),
                    "moodSelfRating", d.getMoodSelfRating() == null ? 0 : d.getMoodSelfRating().intValue(),
                    "taskId", d.getTaskId() == null ? 0 : d.getTaskId(),
                    "preview", content.length() > 80 ? content.substring(0, 80) + "…" : content));
        }
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 100);
        int skip = Math.min(p * s, items.size());
        return Map.of("items", items.subList(skip, Math.min(skip + s, items.size())),
                "page", p, "size", s, "total", items.size());
    }

    /** 详情 = 原文 + 关联情绪标签（当日轨迹）+ 报告跳转元数据（含 stale） */
    public Map<String, Object> detail(long userId, long id) {
        DiaryEntity d = mustGet(userId, id);
        List<Map<String, Object>> reports = new ArrayList<>();
        if (d.getTaskId() != null) {
            for (ReportEntity r : reportRepo.findByTaskId(d.getTaskId())) {
                if (r.getDeletedAt() != null) continue;
                reports.add(Map.of("id", r.getId(), "type", r.getType(), "title", r.getTitle(),
                        "riskLevel", r.getRiskLevel(), "stale", r.getStale().intValue()));
            }
        }
        List<Map<String, Object>> emotions = new ArrayList<>();
        for (EmotionTrajectoryEntity tr : trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, d.getRecordDate(), d.getRecordDate())) {
            emotions.add(Map.of("sourceType", tr.getSourceType(), "emotion", tr.getPrimaryEmotion(),
                    "valence", tr.getValence(), "intensity", tr.getIntensity(),
                    "eventTags", readJsonArray(tr.getEventTags())));
        }
        var out = new java.util.HashMap<String, Object>();
        out.put("id", d.getId());
        out.put("recordDate", d.getRecordDate().toString());
        out.put("moodSelfRating", d.getMoodSelfRating() == null ? 0 : d.getMoodSelfRating().intValue());
        out.put("content", decrypt(userId, d));
        out.put("taskId", d.getTaskId() == null ? 0 : d.getTaskId());
        out.put("reports", reports);
        out.put("emotions", emotions);
        return out;
    }

    /** 编辑：改写密文 + 关联报告置 stale（旧分析对新文本失效） */
    @Transactional
    public Map<String, Object> update(long userId, long id, String content, Short mood) {
        checkLen(content);
        DiaryEntity d = mustGet(userId, id);
        d.setContentEnc(crypto.encryptUserField(userId, content));
        if (mood != null) d.setMoodSelfRating(mood);
        diaryRepo.save(d);
        markStale(d.getTaskId());
        return Map.of("id", d.getId(), "staled", d.getTaskId() != null);
    }

    /** 删除：软删除 + 关联报告 stale */
    @Transactional
    public void delete(long userId, long id) {
        DiaryEntity d = mustGet(userId, id);
        d.setDeletedAt(java.time.Instant.now());
        diaryRepo.save(d);
        markStale(d.getTaskId());
    }

    /** 按新内容重新分析：复用 DIARY_PIPELINE 再跑一轮，日记改挂新任务。
     *  不加 @Transactional：orchestrator.submit 立即派发给虚拟线程，未提交的行对其不可见 */
    public String reanalyze(long userId, long id) {
        DiaryEntity d = mustGet(userId, id);
        String text = decrypt(userId, d);
        var input = mapper.createObjectNode();
        input.put("diaryText", text);
        input.put("recordDate", d.getRecordDate().toString());
        TaskInstanceEntity t = orchestrator.submit(userId, "DIARY_PIPELINE", input,
                "diary-re-" + id + "-" + System.nanoTime());
        d.setTaskId(t.getId());
        diaryRepo.save(d);
        return t.getTaskNo();
    }

    // ---------------- 草稿 ----------------

    @Transactional
    public void saveDraft(long userId, String content) {
        if (content == null || content.isBlank()) { clearDraft(userId); return; }
        checkLen(content);
        DraftEntity dr = draftRepo.findByUserIdAndKind(userId, DRAFT_KIND)
                .orElseGet(() -> {
                    DraftEntity n = new DraftEntity();
                    n.setUserId(userId);
                    n.setKind(DRAFT_KIND);
                    return n;
                });
        dr.setContentEnc(crypto.encryptUserField(userId, content));
        draftRepo.save(dr);
    }

    public String getDraft(long userId) {
        return draftRepo.findByUserIdAndKind(userId, DRAFT_KIND)
                .map(d -> {
                    try {
                        return crypto.decryptUserField(userId, d.getContentEnc());
                    } catch (Exception e) {
                        return "";
                    }
                }).orElse("");
    }

    @Transactional
    public void clearDraft(long userId) {
        draftRepo.findByUserIdAndKind(userId, DRAFT_KIND).ifPresent(draftRepo::delete);
    }

    // ---------------- 内部 ----------------

    private DiaryEntity mustGet(long userId, long id) {
        return diaryRepo.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "日记不存在"));
    }

    private String decrypt(long userId, DiaryEntity d) {
        try {
            return crypto.decryptUserField(userId, d.getContentEnc());
        } catch (Exception e) {
            log.warn("diary {} decrypt failed for user {}: {}", d.getId(), userId, e.toString());
            throw new BizException(ErrorCode.BAD_PARAMS, "该日记无法解密");
        }
    }

    private void markStale(Long taskId) {
        if (taskId == null) return;
        for (ReportEntity r : reportRepo.findByTaskId(taskId)) {
            if (r.getDeletedAt() == null && r.getStale() == 0) {
                r.setStale((short) 1);
                reportRepo.save(r);
            }
        }
    }

    private void checkLen(String content) {
        if (content == null || content.isBlank()) throw new BizException(ErrorCode.BAD_PARAMS, "内容不能为空");
        if (content.length() > MAX_LEN) throw new BizException(ErrorCode.BAD_PARAMS, "最多 " + MAX_LEN + " 字");
    }

    private Short readMood(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        int v = n.asInt();
        return v >= 1 && v <= 5 ? (short) v : null;
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return LocalDate.now(zone());
        }
    }

    private JsonNode readJsonArray(String json) {
        try {
            return json == null || json.isBlank() ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
