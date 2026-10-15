package com.tinqa.procurement.security.service;

import com.tinqa.procurement.security.model.User;

/**
 * The account behind the current request, as authenticated by the security filters. Never taken from the
 * request body.
 */
public interface CurrentUserProvider {

    /** Loaded fresh from the database. */
    User getCurrentUser();

    Long getCurrentUserId();

    /** Without a database lookup. */
    String getCurrentUsername();
}
