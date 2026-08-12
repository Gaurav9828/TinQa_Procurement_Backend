package com.tinqa.procurement.security.service;

import com.tinqa.procurement.security.model.User;

public interface CurrentUserProvider {

    User getCurrentUser();
}