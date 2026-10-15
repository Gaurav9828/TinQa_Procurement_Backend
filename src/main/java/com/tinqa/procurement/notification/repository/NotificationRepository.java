package com.tinqa.procurement.notification.repository;

import com.tinqa.procurement.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

// Reads go through NotificationRecipientRepository: what a user sees is their recipient rows
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
