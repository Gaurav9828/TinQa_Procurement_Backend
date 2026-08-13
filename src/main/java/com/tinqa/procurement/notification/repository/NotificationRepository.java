package com.tinqa.procurement.notification.repository;

import com.tinqa.procurement.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    @Query("""
            SELECT DISTINCT n
            FROM Notification n
            LEFT JOIN NotificationRecipient nr
                ON nr.notification = n
            LEFT JOIN nr.user u
            WHERE n.broadcast = true
               OR u.username = :username
            ORDER BY n.createdAt DESC
            """)
    List<Notification> findForUser(
            @Param("username") String username
    );

    @Query("""
            SELECT COUNT(nr)
            FROM NotificationRecipient nr
            JOIN nr.notification n
            JOIN nr.user u
            WHERE nr.read = false
              AND u.username = :username
            """)
    long countUnreadForUser(
            @Param("username") String username
    );

    @Query("""
            SELECT n
            FROM Notification n
            LEFT JOIN NotificationRecipient nr
                ON nr.notification = n
            LEFT JOIN nr.user u
            WHERE n.id = :notificationId
              AND (
                    n.broadcast = true
                    OR u.username = :username
                  )
            """)
    Notification findForUser(
            @Param("notificationId") Long notificationId,
            @Param("username") String username
    );

    @Query("""
            SELECT n
            FROM Notification n
            JOIN NotificationRecipient nr
                ON nr.notification = n
            JOIN nr.user u
            WHERE u.username = :username
              AND nr.read = false
            ORDER BY n.createdAt DESC
            """)
    List<Notification> findUnreadForUser(
            @Param("username") String username
    );
}