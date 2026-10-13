package com.panfan.hivelineage.v2.frontend;

/** Reads one unexpanded unit so the orchestrator can apply the current Session variables. */
public interface ScriptUnitReader {
    ScriptReadResult readNext(String sourceId, String script, ScriptCursor cursor, ScriptOptions options);
}
