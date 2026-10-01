package com.platform.security;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static AuthenticatedUser getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Unauthenticated request");
        }
        return user;
    }

    public static UUID getAuthenticatedUserId() {
        return getAuthenticatedUser().id();
    }
}
