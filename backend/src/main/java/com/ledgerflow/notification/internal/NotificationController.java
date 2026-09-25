package com.ledgerflow.notification.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.notification.NotificationService;
import com.ledgerflow.shared.Cursor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class NotificationController {
    private final NotificationService notifications;
    NotificationController(NotificationService notifications) { this.notifications = notifications; }
    @GetMapping("/api/v1/notifications")
    Cursor.Page<NotificationService.Notification> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor) { return notifications.list(merchant, limit, cursor); }
}
