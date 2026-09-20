package com.soulvoyage.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** RBAC 权限位（role_permission 表，schema.sql 种子 + RbacSeeder 兜底） */
@Getter
@Setter
@Entity
@Table(name = "role_permission",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_perm", columnNames = {"role", "permission_code"}))
public class RolePermissionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;
}
