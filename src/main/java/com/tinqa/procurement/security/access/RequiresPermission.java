package com.tinqa.procurement.security.access;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The action an endpoint performs. The caller must hold it (that is, hold its {@link PermissionGroup}); otherwise
 * the request is refused with 403 before the method runs. A method-level annotation replaces a class-level one.
 * The {@code {value}} placeholder is filled in by the AnnotationTemplateExpressionDefaults bean in SecurityConfig.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permission.AUTHORITY_PREFIX + "{value}')")
public @interface RequiresPermission {

    Permission value();
}
