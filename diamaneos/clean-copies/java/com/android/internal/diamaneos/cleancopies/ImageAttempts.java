/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/** Live URI attempts have priority over delivery history at the admission cap. */
final class ImageAttempts<T> {
    private final LinkedHashMap<String, Ticket<T>> latest = new LinkedHashMap<>();

    static final class Ticket<T> {
        final String key;
        final WeakReference<T> owner;
        boolean delivered;

        Ticket(String key, T owner) {
            this.key = key;
            this.owner = new WeakReference<>(owner);
        }
    }

    synchronized Ticket<T> begin(String key, T owner) {
        if (!latest.containsKey(key) && latest.size() >= 512) {
            final var tickets = latest.values().iterator();
            while (tickets.hasNext()) {
                if (tickets.next().delivered) {
                    tickets.remove();
                    break;
                }
            }
            if (latest.size() >= 512) return null;
        }
        final Ticket<T> ticket = new Ticket<>(key, owner);
        latest.remove(key);
        latest.put(key, ticket);
        return ticket;
    }

    synchronized T latest(String key) {
        final Ticket<T> ticket = latest.get(key);
        return ticket == null ? null : ticket.owner.get();
    }

    synchronized boolean current(Ticket<T> ticket) {
        return ticket != null && latest.get(ticket.key) == ticket;
    }
}
