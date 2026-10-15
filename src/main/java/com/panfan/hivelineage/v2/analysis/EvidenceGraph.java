package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete immutable proof DAG, including intermediate expression facts.
 */
public final class EvidenceGraph {
    private final Map<EvidenceId, Dependency> dependencies;
    private final Map<EvidenceId, Evidence> evidence;

    public EvidenceGraph(Map<EvidenceId, Dependency> dependencies) {
        this.dependencies = ModelCollections.immutableMap(dependencies);
        Map<EvidenceId, Evidence> declarations = new LinkedHashMap<EvidenceId, Evidence>();
        Map<EvidenceId, Integer> remaining = new LinkedHashMap<EvidenceId, Integer>();
        Map<EvidenceId, List<EvidenceId>> children = new LinkedHashMap<EvidenceId, List<EvidenceId>>();
        ArrayDeque<EvidenceId> ready = new ArrayDeque<EvidenceId>();
        for (Map.Entry<EvidenceId, Dependency> entry : this.dependencies.entrySet()) {
            Evidence proof = entry.getValue().getEvidence();
            if (!entry.getKey().equals(proof.getId())) {
                throw new IllegalArgumentException("Evidence index key differs from declaration");
            }
            declarations.put(entry.getKey(), proof);
            remaining.put(entry.getKey(), proof.getParentEvidence().size());
            if (proof.getParentEvidence().isEmpty()) {
                ready.add(entry.getKey());
            }
            for (EvidenceId parent : proof.getParentEvidence()) {
                if (!this.dependencies.containsKey(parent)) {
                    throw new IllegalArgumentException("Evidence parent is not retained: " + parent.getValue());
                }
                children.computeIfAbsent(parent, ignored -> new ArrayList<EvidenceId>()).add(entry.getKey());
            }
        }
        int completed = 0;
        while (!ready.isEmpty()) {
            EvidenceId parent = ready.removeFirst();
            completed++;
            for (EvidenceId child : children.getOrDefault(parent, Collections.emptyList())) {
                int pending = remaining.get(child) - 1;
                remaining.put(child, pending);
                if (pending == 0) {
                    ready.add(child);
                }
            }
        }
        if (completed != this.dependencies.size()) {
            throw new IllegalArgumentException("Evidence graph contains a cycle");
        }
        this.evidence = ModelCollections.immutableMap(declarations);
    }

    public static EvidenceGraph empty() {
        return new EvidenceGraph(Collections.<EvidenceId, Dependency>emptyMap());
    }

    public Map<EvidenceId, Dependency> getDependencies() {
        return dependencies;
    }

    public Map<EvidenceId, Evidence> getEvidence() {
        return evidence;
    }
}
