package com.tinqa.procurement.security.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAuditEventRepository extends JpaRepository<AuthAuditEvent, Long> {
}
