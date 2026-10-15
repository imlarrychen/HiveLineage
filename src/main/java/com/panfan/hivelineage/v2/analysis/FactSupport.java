package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;

final class FactSupport implements EvidenceFactory {
    private final Map<EvidenceId, Dependency> proof = new LinkedHashMap<EvidenceId, Dependency>();
    private final int maxEvidenceEdges;
    private long proofEdges;

    FactSupport() {
        this(Integer.MAX_VALUE);
    }

    FactSupport(int maxEvidenceEdges) {
        if (maxEvidenceEdges < 0) {
            throw new IllegalArgumentException("maxEvidenceEdges must be nonnegative");
        }
        this.maxEvidenceEdges = maxEvidenceEdges;
    }

    @Override
    public Dependency dependency(LineageEndpoint source, LineageEndpoint target, DependencyRole role, DependencyPrecision precision,
                                 NodeId operation, String rule, List<EvidenceId> parents, Optional<SourceRef> sourceRef) {
        StringBuilder identity = new StringBuilder(part(operation.getValue()) + part(rule) + endpointKey(source) + endpointKey(target)
                + part(role.name()) + part(precision.name()));
        identity.append(parents.size()).append(':');
        for (EvidenceId parent : parents) {
            identity.append(part(parent.getValue()));
        }
        identity.append(sourceRef.isPresent() ? "present:" : "absent:");
        if (sourceRef.isPresent()) {
            SourceRef ref = sourceRef.get();
            identity.append(part(ref.getSourceId())).append(':').append(ref.getSpan().getStart()).append(':').append(ref.getSpan().getEnd());
        }
        Evidence evidence = new Evidence(new EvidenceId("proof:" + digest(identity.toString())),
                operation, rule, parents, sourceRef);
        Dependency result = new Dependency(source, target, role, precision, Optional.empty(), evidence);
        capture(Collections.singletonList(result));
        return result;
    }

    @Override
    public List<Dependency> retarget(List<Dependency> dependencies, LineageEndpoint target, NodeId operation,
                                     String rule, DependencyRole forcedRole, Optional<SourceRef> sourceRef) {
        List<Dependency> result = new ArrayList<Dependency>();
        capture(dependencies);
        for (Dependency dependency : dependencies) {
            result.add(dependency(dependency.getSource(), target, forcedRole == null ? dependency.getRole() : forcedRole,
                    dependency.getPrecision(), operation,
                    forcedRole == null && dependency.getEvidence().getRuleId().startsWith("window.")
                            ? dependency.getEvidence().getRuleId() : rule,
                    Collections.singletonList(dependency.getEvidence().getId()), sourceRef));
        }
        return distinct(result);
    }

    void capture(List<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            EvidenceId id = dependency.getEvidence().getId();
            Dependency previous = proof.get(id);
            if (previous != null && !sameProof(previous, dependency)) {
                throw new IllegalArgumentException("Evidence identity is reused for different proof: " + id.getValue());
            }
            if (previous == null) {
                long additional = 1L + new HashSet<EvidenceId>(dependency.getEvidence().getParentEvidence()).size();
                if (proofEdges + additional > maxEvidenceEdges) {
                    throw new ResourceLimitException("Incremental dependency/evidence graph limit exceeded");
                }
                proofEdges += additional;
            }
            proof.put(id, dependency);
        }
    }

    @Override
    public void retain(Dependency dependency) {
        capture(Collections.singletonList(dependency));
    }

    EvidenceGraph graph() {
        return new EvidenceGraph(proof);
    }

    long proofEdges() {
        return proofEdges;
    }

    static boolean sameProof(Dependency left, Dependency right) {
        return endpointKey(left.getSource()).equals(endpointKey(right.getSource()))
                && endpointKey(left.getTarget()).equals(endpointKey(right.getTarget()))
                && left.getRole() == right.getRole() && left.getPrecision() == right.getPrecision()
                && left.getEvidence().getOperationId().equals(right.getEvidence().getOperationId())
                && left.getEvidence().getRuleId().equals(right.getEvidence().getRuleId())
                && left.getEvidence().getParentEvidence().equals(right.getEvidence().getParentEvidence())
                && left.getEvidence().getSourceRef().equals(right.getEvidence().getSourceRef());
    }

    private static String digest(String identity) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not support SHA-256", exception);
        }
    }

    static List<Dependency> distinct(List<Dependency> values) {
        LinkedHashMap<String, Dependency> result = new LinkedHashMap<String, Dependency>();
        for (Dependency value : values) {
            String key = endpointKey(value.getSource()) + "->" + endpointKey(value.getTarget()) + ":" + value.getRole()
                    + ":" + value.getPrecision() + ":" + value.getEvidence().getRuleId();
            if (!result.containsKey(key)) {
                result.put(key, value);
            }
        }
        return new ArrayList<Dependency>(result.values());
    }

    static String endpointKey(LineageEndpoint endpoint) {
        if (endpoint instanceof ColumnEndpoint) {
            ColumnEndpoint column = (ColumnEndpoint) endpoint;
            return "column:" + part(column.getObjectId().getValue()) + part(column.getFieldId().getValue()) + parts(column.getFieldPath());
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            DirectFieldEndpoint field = (DirectFieldEndpoint) endpoint;
            return "direct:" + part(field.getOwner().getValue()) + part(field.getFieldId().getValue()) + parts(field.getFieldPath());
        }
        if (endpoint instanceof UnknownDirectEndpoint) {
            UnknownDirectEndpoint unknown = (UnknownDirectEndpoint) endpoint;
            return "display:" + part(unknown.getOwner().map(value -> value.getValue()).orElse("")) + parts(unknown.getDisplayName().getParts());
        }
        if (endpoint instanceof SlotEndpoint) {
            return "slot:" + part(((SlotEndpoint) endpoint).getSlotId().getValue());
        }
        if (endpoint instanceof RowSetEndpoint) {
            return "rows:" + part(((RowSetEndpoint) endpoint).getRelationId().getValue());
        }
        if (endpoint instanceof ConstantEndpoint) {
            return "constant:" + part(((ConstantEndpoint) endpoint).getValueRepresentation());
        }
        if (endpoint instanceof ParameterEndpoint) {
            ParameterEndpoint parameter = (ParameterEndpoint) endpoint;
            return "parameter:" + part(parameter.getNamespace()) + part(parameter.getName());
        }
        if (endpoint instanceof ExternalDatasetEndpoint) {
            ExternalDatasetEndpoint external = (ExternalDatasetEndpoint) endpoint;
            return "external:" + part(external.getSourceKind()) + part(external.getDatasetIdentity());
        }
        if (endpoint instanceof UnknownEndpoint) {
            StringBuilder key = new StringBuilder("unknown:");
            for (Diagnostic diagnostic : ((UnknownEndpoint) endpoint).getDiagnostics().getDiagnostics()) {
                key.append(part(diagnostic.getCode())).append(part(diagnostic.getMessage()));
                if (diagnostic.getSourceRef() != null) {
                    key.append(part(diagnostic.getSourceRef().getSourceId())).append(diagnostic.getSourceRef().getSpan().getStart())
                            .append(':').append(diagnostic.getSourceRef().getSpan().getEnd());
                }
            }
            return key.toString();
        }
        throw new IllegalArgumentException("Unsupported endpoint kind " + endpoint.getClass().getName());
    }

    private static String part(String value) {
        return value.length() + ":" + value;
    }

    private static String parts(List<String> values) {
        StringBuilder result = new StringBuilder();
        result.append(values.size()).append(':');
        for (String value : values) {
            result.append(part(value));
        }
        return result.toString();
    }

    static DiagnosticBag diagnostic(String code, String message, SourceRef ref) {
        return new DiagnosticBag(Collections.singletonList(new Diagnostic(AnalysisStage.ANALYSIS, code, message, ref)));
    }

    static AnalysisQuality quality(List<Dependency> dependencies) {
        boolean unknown = false;
        boolean conservative = false;
        for (Dependency dependency : dependencies) {
            unknown |= dependency.getSource() instanceof UnknownEndpoint || dependency.getPrecision() == DependencyPrecision.UNKNOWN;
            conservative |= dependency.getPrecision() == DependencyPrecision.CONSERVATIVE;
        }
        return new AnalysisQuality(unknown ? Validity.UNDETERMINED : Validity.VALID, unknown ? Coverage.PARTIAL : Coverage.COMPLETE,
                unknown ? DependencyPrecision.UNKNOWN : conservative ? DependencyPrecision.CONSERVATIVE : DependencyPrecision.STRUCTURAL,
                EffectState.NOT_APPLIED);
    }

    static DiagnosticBag diagnostics(List<Dependency> dependencies) {
        List<Diagnostic> result = new ArrayList<Diagnostic>();
        for (Dependency dependency : dependencies) {
            if (dependency.getSource() instanceof UnknownEndpoint) {
                result.addAll(((UnknownEndpoint) dependency.getSource()).getDiagnostics().getDiagnostics());
            }
        }
        return new DiagnosticBag(result);
    }
}
