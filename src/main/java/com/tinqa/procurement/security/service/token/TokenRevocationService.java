package com.tinqa.procurement.security.service.token;

import java.time.Duration;

public interface TokenRevocationService {

    void revoke(String jti, Duration remainingLifetime);

    boolean isRevoked(String jti);
}