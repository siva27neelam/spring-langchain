package com.loanprocessing.security;

import com.loanprocessing.model.ActorType;

import java.util.UUID;

public final class RequestContext {
    public record Ids(UUID correlationId, String requestId, String actorId, ActorType actorType) {
    }

    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(Ids ids) {
        CURRENT.set(ids);
    }

    public static Ids get() {
        Ids ids = CURRENT.get();
        if (ids != null) {
            return ids;
        }
        return new Ids(UUID.randomUUID(), UUID.randomUUID().toString(), "system", ActorType.SYSTEM);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
