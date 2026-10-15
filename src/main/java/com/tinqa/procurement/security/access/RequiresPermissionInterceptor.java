package com.tinqa.procurement.security.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks an endpoint's {@link RequiresPermission} before its arguments are resolved, so a caller without the
 * permission is refused with 403 before the request body is read or validated. The annotation's @PreAuthorize still
 * runs as the second check.
 */
@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresPermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPermission.class);
        }
        if (required == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String authority = required.value().authority();
        if (authentication == null
                || authentication.getAuthorities().stream().noneMatch(granted -> authority.equals(granted.getAuthority()))) {
            throw new AccessDeniedException("Missing permission " + required.value().name());
        }
        return true;
    }
}
