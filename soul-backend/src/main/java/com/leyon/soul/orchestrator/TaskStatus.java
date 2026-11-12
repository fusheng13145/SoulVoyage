package com.leyon.soul.orchestrator;

/** 任务状态（B-03）：DB 值保持原样，仅把散落的字符串收敛为类型。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED;

    public static TaskStatus of(String raw) {
        if (raw != null) {
            for (TaskStatus s : values()) {
                if (s.name().equals(raw)) return s;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + raw);
    }

    public boolean terminal() {
        return this != PENDING && this != RUNNING;
    }
}
