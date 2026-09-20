package com.soulvoyage.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, Long> {
    boolean existsByRoleAndPermissionCode(String role, String permissionCode);
}
