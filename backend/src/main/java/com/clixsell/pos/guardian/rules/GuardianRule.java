package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianResponses.Action;
import com.clixsell.pos.guardian.GuardianResponses.Actor;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Guardian detection rule (G1 … G9). A rule is a plain calculation on the
 * store's data: same data → same alerts. Every alert carries the numbers and
 * the limit it was compared with, and the records it is based on.
 */
public interface GuardianRule {

    String NOT_RECORDED = "NOT_RECORDED";
    String ACTIVITY_LOG = "ACTIVITY_LOG";

    List<Alert> evaluate(GuardianData data);

    static Alert alert(String key, String rule, String code, Severity severity, String category,
                       Map<String, Object> params, List<Ref> evidence, Actor actor, String actorNote,
                       LocalDateTime at, Action action) {
        return new Alert(key, rule, code, severity, category, params, evidence, actor, actorNote, at,
                at == null ? null : at.toLocalDate(), action);
    }

    /** Ordered key / value pairs (null values allowed). */
    static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    /** The user recorded by the backend in the activity log, or null. */
    static Actor actor(LogFact log) {
        return log == null ? null : new Actor(log.userId(), log.userName(), ACTIVITY_LOG);
    }

    static Ref sale(long id, String invoiceNumber) {
        return new Ref("SALE", id, invoiceNumber);
    }

    static Ref product(Long id, String name) {
        return new Ref("PRODUCT", id, name);
    }

    static Action action(String type, Long id) {
        return new Action(type, id);
    }
}
