package com.clixsell.pos.activity;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Writes the activity log (Guardian Mode, report p.18–19 and Figure 3.1).
 *
 * <p>Rules:
 * <ul>
 *   <li>The user is ALWAYS the logged-in user of the request ({@link CurrentUser}),
 *       never a user id sent by the app and never the cashier who started an invoice.</li>
 *   <li>The store is always the logged-in user's store.</li>
 *   <li>It runs only inside the caller's transaction ({@link Propagation#MANDATORY}):
 *       the action and its log row are saved together or not at all.</li>
 *   <li>It records even while Guardian Mode is off, so the history has no gaps
 *       (decision D6).</li>
 * </ul>
 */
@Service
public class ActivityLogService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ActivityLogRepository log;

    public ActivityLogService(ActivityLogRepository log) {
        this.log = log;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CurrentUser user, ActivityEventType type, String entityType, Long entityId,
                       String entityName, Map<String, ?> details) {
        long storeId = user.requireStoreId();
        String json = details == null || details.isEmpty() ? null : JSON.writeValueAsString(details);
        log.save(new ActivityLog(storeId, user.userId(), type, entityType, entityId, entityName, json,
                LocalDateTime.now().withNano(0)));
    }
}
