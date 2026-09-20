package com.soulvoyage.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 方法级权限求值（@PreAuthorize("@perms.has('admin:task')")）：
 * 权限真源是 role_permission 表——改权限不动代码；表空/未命中一律拒绝。
 */
@Service("perms")
@RequiredArgsConstructor
public class PermissionService {

    private final RolePermissionRepository perms;

    public boolean has(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthPrincipal p)) {
            return false;
        }
        return perms.existsByRoleAndPermissionCode(p.role(), code);
    }
}
