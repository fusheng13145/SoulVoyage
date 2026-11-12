package com.leyon.soul.orchestrator;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 显式状态机（B-03）：写库前判定迁移合法性，非法迁移视为内部 bug。
 * FAILED→RUNNING 供 A1 手动重跑；PARTIAL_SUCCESS→CANCELLED / CANCELLED→CANCELLED
 * 为兼容既有 cancel 语义（原实现只拦 SUCCESS/FAILED）。
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED.put(TaskStatus.PENDING, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.RUNNING, EnumSet.of(
                TaskStatus.SUCCESS, TaskStatus.PARTIAL_SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.FAILED, EnumSet.of(TaskStatus.RUNNING));
        ALLOWED.put(TaskStatus.PARTIAL_SUCCESS, EnumSet.of(TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.CANCELLED, EnumSet.of(TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.SUCCESS, EnumSet.noneOf(TaskStatus.class));
    }

    private TaskStateMachine() {}

    public static boolean canTransit(TaskStatus from, TaskStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(TaskStatus from, TaskStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException("非法任务状态迁移: " + from + " -> " + to);
        }
    }
}
