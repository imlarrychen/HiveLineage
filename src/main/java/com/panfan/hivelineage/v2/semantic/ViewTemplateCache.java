package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;

import java.util.Optional;

/**
 * Stores syntax templates only; no task-specific bound graph or data version is shared.
 */
public interface ViewTemplateCache {
    Optional<SyntaxTemplate> get(ViewTemplateKey key);

    void put(ViewTemplateKey key, SyntaxTemplate template);
}
