package com.tinqa.procurement.outreach;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutreachBusinessRepository extends JpaRepository<OutreachBusiness, Long>, JpaSpecificationExecutor<OutreachBusiness> {

    Optional<OutreachBusiness> findFirstByPhoneAndIdNot(String phone, Long excludeId);

    @Query("SELECT b FROM OutreachBusiness b WHERE lower(b.email) = lower(:email) AND b.id <> :excludeId")
    Optional<OutreachBusiness> findByEmailExcluding(@Param("email") String email, @Param("excludeId") Long excludeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM OutreachBusiness b WHERE b.id = :id")
    Optional<OutreachBusiness> findByIdForUpdate(@Param("id") Long id);

    /**
     * Rewrites a business's contact summary from its contacts in one statement: counts, the latest contact (by when it
     * happened) with its interest, rating and follow-up. Earlier contacts' follow-ups are superseded, so their reminders
     * are marked done.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH latest AS (
                SELECT id, contacted_at, logged_by, interest, rating, follow_up_at
                FROM outreach_contact WHERE business_id = :businessId
                ORDER BY contacted_at DESC, id DESC LIMIT 1
            ), counts AS (
                SELECT COUNT(*) AS contacts, COUNT(*) FILTER (WHERE next_action = 'DEMO_REQUESTED') AS demos
                FROM outreach_contact WHERE business_id = :businessId
            ), superseded AS (
                UPDATE outreach_contact SET follow_up_reminded_at = now()
                WHERE business_id = :businessId AND follow_up_at IS NOT NULL AND follow_up_reminded_at IS NULL
                  AND id <> (SELECT id FROM latest)
                RETURNING id
            )
            UPDATE outreach_business b SET
                contact_count = counts.contacts,
                demo_request_count = counts.demos,
                latest_contact_id = (SELECT id FROM latest),
                last_contact_at = (SELECT contacted_at FROM latest),
                last_contact_by = (SELECT logged_by FROM latest),
                latest_interest = (SELECT interest FROM latest),
                latest_rating = (SELECT rating FROM latest),
                next_follow_up_at = (SELECT follow_up_at FROM latest),
                version = b.version + 1
            FROM counts
            WHERE b.id = :businessId
            """, nativeQuery = true)
    int refreshSummary(@Param("businessId") Long businessId);

    /**
     * The outreach dashboard in one statement, as JSON: totals for [from, to), contacts and businesses per day (in
     * the given time zone), per executive, per channel and per interest, and the follow-ups due before dueBefore.
     */
    @Query(value = """
            WITH c AS (
                SELECT business_id, logged_by, channel, interest, next_action, contacted_at
                FROM outreach_contact WHERE contacted_at >= :from AND contacted_at < :to
            )
            SELECT CAST(json_build_object(
                'totalContacts', (SELECT COUNT(*) FROM c),
                'businessesContacted', (SELECT COUNT(DISTINCT business_id) FROM c),
                'newBusinesses', (SELECT COUNT(*) FROM outreach_business WHERE created_at >= :from AND created_at < :to),
                'demoRequests', (SELECT COUNT(*) FROM c WHERE next_action = 'DEMO_REQUESTED'),
                'followUpsDue', (SELECT COUNT(*) FROM outreach_business WHERE next_follow_up_at < :dueBefore),
                'byDay', COALESCE((SELECT json_agg(d ORDER BY d.date) FROM (
                    SELECT CAST(timezone(:zone, contacted_at) AS date) AS date, COUNT(*) AS contacts,
                           COUNT(DISTINCT business_id) AS businesses
                    FROM c GROUP BY 1) d), '[]'),
                'byExecutive', COALESCE((SELECT json_agg(x ORDER BY x.contacts DESC, x.name) FROM (
                    SELECT c.logged_by AS "userId",
                           COALESCE(NULLIF(TRIM(MAX(e.display_name)), ''), MAX(u.username)) AS name,
                           COUNT(*) AS contacts, COUNT(DISTINCT c.business_id) AS businesses,
                           COUNT(*) FILTER (WHERE c.interest = 'INTERESTED') AS interested
                    FROM c JOIN users u ON u.id = c.logged_by LEFT JOIN employee e ON e.user_id = c.logged_by
                    GROUP BY c.logged_by) x), '[]'),
                'byChannel', COALESCE((SELECT json_object_agg(channel, n) FROM (
                    SELECT channel, COUNT(*) AS n FROM c GROUP BY channel) ch), '{}'),
                'byInterest', COALESCE((SELECT json_object_agg(interest, n) FROM (
                    SELECT interest, COUNT(*) AS n FROM c GROUP BY interest) i), '{}')
            ) AS text)
            """, nativeQuery = true)
    String summary(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to,
                   @Param("dueBefore") java.time.Instant dueBefore, @Param("zone") String zone);
}
