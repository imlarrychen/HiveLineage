package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Optional;

/**
 * Rendering only; never used to infer dependencies.
 */
public interface SourceRenderer {
    Optional<String> render(SourceRef sourceRef);
}
