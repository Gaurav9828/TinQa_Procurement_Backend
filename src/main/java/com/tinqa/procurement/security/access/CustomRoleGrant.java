package com.tinqa.procurement.security.access;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A custom role as access sees it: what it is called and the groups it carries (without SELF_SERVICE, which every
 * employee gets anyway). Detached from the database, so it can live in a {@link UserAccess} after the query ends.
 */
public record CustomRoleGrant(Long id, String code, String label, String department, Set<PermissionGroup> groups) {

    public CustomRoleGrant {
        groups = groups == null || groups.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(groups));
    }
}
