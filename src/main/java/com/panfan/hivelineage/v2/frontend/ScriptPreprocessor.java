package com.panfan.hivelineage.v2.frontend;

/** Optional preprocessing contract; implementations must not expand variables or infer SQL sources. */
public interface ScriptPreprocessor {
    ScriptPreprocessResult preprocess(String sourceId, String script, ScriptOptions options);
}
