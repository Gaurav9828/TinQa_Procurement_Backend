package com.tinqa.procurement.outreach;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutreachContactRepository extends JpaRepository<OutreachContact, Long>, JpaSpecificationExecutor<OutreachContact> {

    @Query(value = "SELECT c FROM OutreachContact c WHERE c.businessId = :businessId ORDER BY c.contactedAt DESC, c.id DESC",
            countQuery = "SELECT COUNT(c) FROM OutreachContact c WHERE c.businessId = :businessId")
    Page<OutreachContact> findByBusiness(@Param("businessId") Long businessId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OutreachContact c WHERE c.id = :id")
    Optional<OutreachContact> findByIdForUpdate(@Param("id") Long id);

    /**
     * Follow-ups due before the cutoff: each business's latest contact, when it asks for a follow-up (the business's
     * next_follow_up_at). loggedBy null = everyone's. Oldest first.
     */
    @Query(value = """
            SELECT c FROM OutreachContact c, OutreachBusiness b
            WHERE b.latestContactId = c.id AND b.nextFollowUpAt < :before AND (:loggedBy IS NULL OR c.loggedBy = :loggedBy)
            ORDER BY b.nextFollowUpAt ASC, c.id ASC
            """, countQuery = """
            SELECT COUNT(c) FROM OutreachContact c, OutreachBusiness b
            WHERE b.latestContactId = c.id AND b.nextFollowUpAt < :before AND (:loggedBy IS NULL OR c.loggedBy = :loggedBy)
            """)
    Page<OutreachContact> findFollowUpsDue(@Param("before") Instant before, @Param("loggedBy") Long loggedBy, Pageable pageable);

    // Follow-up reminders that are due (cutoff: 09:00 today has passed -> end of today, else start of today), oldest first
    @Query(value = """
            SELECT id, business_id, logged_by, follow_up_at, next_action FROM outreach_contact
            WHERE follow_up_at IS NOT NULL AND follow_up_reminded_at IS NULL AND follow_up_at < :cutoff
            ORDER BY follow_up_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Object[]> lockRemindersDue(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutreachContact c SET c.followUpRemindedAt = :at WHERE c.id IN :ids")
    int markReminded(@Param("ids") Collection<Long> ids, @Param("at") Instant at);
}
