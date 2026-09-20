package com.soulvoyage.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * RBAC 种子（技术债 7）：role_permission 表在 schema.sql 有种子，但 H2 测试库与增量权限位
 * 靠这里逐条幂等补齐——与 ContentDataSeeder 同思路，表是权限真源、代码零硬编码权限。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RbacSeeder implements ApplicationRunner {

    private static final String[][] SEED = {
            {"USER", "task:submit"}, {"USER", "diary:write"}, {"USER", "simulate:play"},
            {"USER", "report:read"}, {"USER", "archive:export"}, {"USER", "account:delete"},
            {"ADMIN", "task:submit"}, {"ADMIN", "admin:task"}, {"ADMIN", "admin:scene"},
            {"ADMIN", "admin:audit"}, {"ADMIN", "admin:risk:view"}, {"ADMIN", "admin:user"},
    };

    private final RolePermissionRepository repo;

    @Override
    public void run(ApplicationArguments args) {
        int added = 0;
        for (String[] row : SEED) {
            if (!repo.existsByRoleAndPermissionCode(row[0], row[1])) {
                RolePermissionEntity e = new RolePermissionEntity();
                e.setRole(row[0]);
                e.setPermissionCode(row[1]);
                repo.save(e);
                added++;
            }
        }
        if (added > 0) log.info("role_permission seeded rows={}", added);
    }
}
