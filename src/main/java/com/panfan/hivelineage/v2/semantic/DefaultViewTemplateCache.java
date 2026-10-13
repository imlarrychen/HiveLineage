package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Immutable syntax templates only; binding and Session state are request-owned. */
public final class DefaultViewTemplateCache implements ViewTemplateCache {
    private final Map<ViewTemplateKey, Entry> templates = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;
    private final long ttlMillis;
    private final LongSupplier clock;

    public DefaultViewTemplateCache() {
        this(1024, 900_000L, System::currentTimeMillis);
    }

    public DefaultViewTemplateCache(int capacity, long ttlMillis, LongSupplier clock) {
        if (capacity <= 0 || ttlMillis < 0L) {
            throw new IllegalArgumentException("template capacity must be positive and ttl nonnegative");
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Optional<SyntaxTemplate> get(ViewTemplateKey key) {
        Entry entry = templates.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (expired(entry, clock.getAsLong())) {
            templates.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.template);
    }

    @Override
    public synchronized void put(ViewTemplateKey key, SyntaxTemplate template) {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(template, "template");
        long now = clock.getAsLong();
        templates.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        long expiresAt = ttlMillis == 0L ? 0L : now > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : now + ttlMillis;
        templates.putIfAbsent(key, new Entry(template, expiresAt));
        while (templates.size() > capacity) {
            templates.remove(templates.keySet().iterator().next());
        }
    }

    private static boolean expired(Entry entry, long now) {
        return entry.expiresAt > 0L && now >= entry.expiresAt;
    }

    private static final class Entry {
        private final SyntaxTemplate template;
        private final long expiresAt;

        private Entry(SyntaxTemplate template, long expiresAt) {
            this.template = template;
            this.expiresAt = expiresAt;
        }
    }
}
