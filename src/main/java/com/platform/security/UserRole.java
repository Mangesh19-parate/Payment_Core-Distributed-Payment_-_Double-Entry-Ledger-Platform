package com.platform.security;

public enum UserRole {
    ROLE_CUSTOMER,
    ROLE_MAKER,
    ROLE_CHECKER,
    ROLE_REVERSAL_APPROVER,
    ROLE_ADMIN,
    ROLE_AUDITOR;

    public static UserRole fromString(String role) {
        if (role == null) return ROLE_CUSTOMER;
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return UserRole.valueOf(normalized);
    }
}
