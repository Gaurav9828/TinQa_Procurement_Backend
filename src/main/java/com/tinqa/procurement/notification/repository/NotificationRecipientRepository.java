package com.tinqa.procurement.notification.repository;

import com.tinqa.procurement.notification.entity.NotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRecipientRepository
        extends JpaRepository<NotificationRecipient, Long> {

    @Query("""
            SELECT nr
            FROM NotificationRecipient nr
            JOIN FETCH nr.notification n
            WHERE nr.user.username = :username
            ORDER BY n.createdAt DESC
            """)
    List<NotificationRecipient> findMyNotifications(
            @Param("username") String username
    );

    @Query("""
            SELECT COUNT(nr)
            FROM NotificationRecipient nr
            WHERE nr.user.username = :username
              AND nr.read = false
            """)
    long countUnread(
            @Param("username") String username
    );

    @Query("""
            SELECT nr
            FROM NotificationRecipient nr
            JOIN FETCH nr.notification n
            WHERE nr.id = :recipientId
              AND nr.user.username = :username
            """)
    Optional<NotificationRecipient> findMyNotification(
            @Param("recipientId") Long recipientId,
            @Param("username") String username
    );

    @Query("""
            SELECT nr
            FROM NotificationRecipient nr
            WHERE nr.user.username = :username
              AND nr.read = false
            """)
    List<NotificationRecipient> findMyUnreadNotifications(
            @Param("username") String username
    );
}