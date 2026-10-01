package com.platform.security;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.UUID;

@Service
public class AuthorizationService {

    public void requireAccountOwnershipOrAdmin(UUID accountOwnerId) {
        AuthenticatedUser user = SecurityUtils.getAuthenticatedUser();
        if (user.role() == UserRole.ROLE_ADMIN) {
            return;
        }
        if (!user.id().equals(accountOwnerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Access denied: you do not own this account");
        }
    }

    public void requireRole(UserRole... allowedRoles) {
        AuthenticatedUser user = SecurityUtils.getAuthenticatedUser();
        boolean hasRole = Arrays.stream(allowedRoles).anyMatch(r -> r == user.role());
        if (!hasRole) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Access denied: insufficient role privileges");
        }
    }

    public void requireChecker(UUID requestedBy) {
        AuthenticatedUser user = SecurityUtils.getAuthenticatedUser();
        if (user.role() != UserRole.ROLE_CHECKER && user.role() != UserRole.ROLE_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Access denied: requires CHECKER or ADMIN role");
        }
        if (user.id().equals(requestedBy)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Maker-checker violation: cannot approve/reject your own transaction");
        }
    }

    public void requireReversalApprover() {
        AuthenticatedUser user = SecurityUtils.getAuthenticatedUser();
        if (user.role() != UserRole.ROLE_REVERSAL_APPROVER && user.role() != UserRole.ROLE_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Access denied: requires REVERSAL_APPROVER or ADMIN role");
        }
    }
}
