package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.analysis.RowSetEndpoint;
import com.panfan.hivelineage.v2.analysis.DirectFieldEndpoint;
import com.panfan.hivelineage.v2.analysis.UnknownEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotEndpoint;
import com.panfan.hivelineage.v2.analysis.ConstantEndpoint;
import com.panfan.hivelineage.v2.analysis.ParameterEndpoint;
import com.panfan.hivelineage.v2.analysis.ExternalDatasetEndpoint;
import com.panfan.hivelineage.v2.analysis.UnknownDirectEndpoint;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import java.util.List;
import java.util.stream.Collectors;

/** Stable structured inspection output including quality, roles, unknowns and evidence. */
public final class LineageJsonRenderer {
    private LineageJsonRenderer() {
    }

    public static String render(ScriptAnalysisResult result) {
        FormalLineageRecordExporter columns = new DefaultFormalLineageRecordExporter(
            new DefaultSourceRenderer(result.getSources()),
            result.getBasis().map(basis -> basis.getContract().isIncludeTemporaryLineage()).orElse(false));
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"workflowId\":").append(quote(result.getTask().getWorkflowId())).append(",\n")
            .append("  \"taskId\":").append(quote(result.getTask().getTaskId())).append(",\n")
            .append("  \"taskVersion\":").append(quote(result.getTask().getTaskVersion())).append(",\n")
            .append("  \"engine\":").append(quote(result.getEngine().getEngineId())).append(",\n")
            .append("  \"total\":").append(result.getCounts().getTotal()).append(",\n")
            .append("  \"ignored\":").append(result.getCounts().getIgnored()).append(",\n")
            .append("  \"failed\":").append(result.getCounts().getFailed()).append(",\n  \"statements\":[\n");
        boolean first = true;
        for (StatementAnalysisResult statement : result.getStatements()) {
            if (!first) {
                json.append(",\n");
            }
            first = false;
            json.append("    {\"id\":").append(quote(statement.getIdentity().getStatementId()))
                .append(",\"index\":").append(statement.getIdentity().getStatementIndex())
                .append(",\"type\":").append(quote(statement.getStatementType()))
                .append(",\"rawSql\":").append(quote(statement.getRawSql().getText()))
                .append(",\"normalizedSql\":").append(quote(statement.getNormalizedSql().getText()))
                .append(",\"status\":").append(quote(statement.getStatus().name()))
                .append(",\"statusCode\":").append(statement.getStatus().getCode())
                .append(",\"formal\":").append(statement.isFormalLineageProduced())
                .append(",\"failure\":").append(statement.getFailureCode().map(LineageJsonRenderer::quote).orElse("null"));
            json.append(",\"columnLineages\":[").append(columns.exportColumns(statement).stream()
                .map(ColumnLineageJsonRecords::render).collect(Collectors.joining(","))).append("]");
            statement.getFrontendEvidence().ifPresent(frontend -> json.append(",\"frontend\":").append(frontend(frontend)));
            json.append(",\"diagnostics\":[").append(statement.getDiagnostics().getDiagnostics().stream().map(diagnostic ->
                "{\"stage\":" + quote(diagnostic.getStage().name()) + ",\"code\":" + quote(diagnostic.getCode())
                    + ",\"message\":" + quote(diagnostic.getMessage()) + "}").collect(Collectors.joining(","))).append("]");
            statement.getLineage().ifPresent(facts -> {
                json.append(",\"quality\":{\"validity\":").append(quote(facts.getQuality().getValidity().name()))
                    .append(",\"coverage\":").append(quote(facts.getQuality().getCoverage().name()))
                    .append(",\"precision\":").append(quote(facts.getQuality().getPrecision().name())).append("}");
                json.append(",\"writeDependencies\":[").append(dependencies(facts.getWriteDependencies())).append("]");
                json.append(",\"lineageEvidence\":[").append(dependencies(new java.util.ArrayList<>(facts.getEvidenceGraph().getDependencies().values()))).append("]");
                json.append(",\"relations\":[").append(facts.getRelations().stream().map(relation ->
                    "{\"id\":" + quote(relation.getRelationId().getValue()) + ",\"rowSetDependencies\":[" + dependencies(relation.getRowSetFacts())
                        + "],\"outputs\":[" + relation.getOutputFacts().entrySet().stream().map(entry ->
                            "{\"slot\":" + quote(entry.getKey().getValue()) + ",\"dependencies\":[" + dependencies(entry.getValue().getDependencies()) + "]}")
                            .collect(Collectors.joining(",")) + "]}").collect(Collectors.joining(","))).append("]");
            });
            statement.getJoins().ifPresent(facts -> {
                json.append(",\"joinEvidence\":[").append(dependencies(new java.util.ArrayList<>(facts.getEvidenceGraph().getDependencies().values()))).append("]");
                json.append(",\"joins\":[").append(facts.getJoins().stream().map(join ->
                "{\"index\":" + join.getJoinIndex() + ",\"queryBlock\":" + quote(join.getQueryBlockPath()) + ",\"kind\":" + quote(join.getKind().name())
                    + ",\"direct\":[" + join.getDirectReferences().stream().map(LineageJsonRenderer::endpoint).collect(Collectors.joining(","))
                    + "],\"physical\":[" + join.getPhysicalReferences().stream().map(LineageJsonRenderer::endpoint).collect(Collectors.joining(","))
                    + "],\"lexicalReferences\":[" + join.getLexicalReferences().stream().map(LineageJsonRenderer::source).collect(Collectors.joining(",")) + "]}")
                    .collect(Collectors.joining(","))).append("]");
            });
            statement.getBoundUnit().ifPresent(unit -> json.append(",\"executionAssumption\":").append(quote(unit.getExecutionAssumption().name()))
                .append(",\"readVersions\":[").append(unit.getRelations().stream().filter(relation -> relation instanceof Relations.Scan || relation instanceof Relations.DefinitionInstance)
                    .map(LineageJsonRenderer::readVersion).collect(Collectors.joining(","))).append("]")
                .append(",\"boundRelations\":[").append(unit.getRelations().stream().map(relation ->
                    "{\"id\":" + quote(relation.getId().getValue()) + ",\"nodeType\":" + quote(relation.getClass().getSimpleName())
                        + ",\"queryBlock\":" + quote(relation.getQueryBlockId().getValue()) + ",\"inputs\":["
                        + relation.getInputIds().stream().map(id -> quote(id.getValue())).collect(Collectors.joining(","))
                        + "],\"outputSchema\":" + schema(relation.getOutputSchema()) + "}").collect(Collectors.joining(","))).append("]")
                .append(",\"relationReferences\":[").append(unit.getRelationReferences().values().stream().map(reference ->
                    "{\"owner\":" + quote(reference.getId().getValue()) + ",\"aliasParts\":["
                        + reference.getAlias().getParts().stream().map(LineageJsonRenderer::quote).collect(Collectors.joining(","))
                        + "],\"objectId\":" + reference.getObjectId().map(id -> quote(id.getValue())).orElse("null") + "}")
                    .collect(Collectors.joining(","))).append("]")
                .append(",\"fieldHandles\":[").append(fieldHandles(unit)).append("]")
                .append(",\"objects\":[").append(unit.getObjects().values().stream().map(object ->
                "{\"id\":" + quote(object.getId().getValue()) + ",\"name\":" + quote(object.getName().getDisplayName())
                    + ",\"nameParts\":[" + object.getName().getParts().stream().map(LineageJsonRenderer::quote).collect(Collectors.joining(","))
                    + "],\"kind\":" + quote(object.getKind().name()) + ",\"schemaState\":" + quote(object.getSchemaState().name())
                    + ",\"fields\":[" + object.getFields().stream().map(field -> "{\"id\":" + quote(field.getId().getValue())
                        + ",\"name\":" + quote(field.getDisplayName()) + "}").collect(Collectors.joining(",")) + "]}")
                .collect(Collectors.joining(","))).append("],\"writes\":[").append(unit.getWrites().stream().map(write ->
                    "{\"id\":" + quote(write.getId().getValue()) + ",\"mode\":" + quote(write.getWriteMode().name())
                        + ",\"targetObjectId\":" + quote(write.getTargetObject().getValue())
                        + ",\"assignments\":[" + write.getAssignments().stream().map(assignment ->
                            "{\"fieldId\":" + quote(assignment.getTargetField().getId().getValue()) + ",\"name\":" + quote(assignment.getTargetField().getDisplayName())
                                + ",\"expression\":" + quote(assignment.getExpression().getId().getValue()) + "}").collect(Collectors.joining(",")) + "]"
                        + ",\"beforeVersion\":" + quote(write.getBeforeVersion().getState().name()) + ",\"beforeVersionId\":" + write.getBeforeVersion().getId().map(id -> quote(id.getValue())).orElse("null") + ",\"afterVersion\":" + quote(write.getAfterVersion().getState().name()) + ",\"afterVersionId\":" + write.getAfterVersion().getId().map(id -> quote(id.getValue())).orElse("null") + "}")
                    .collect(Collectors.joining(","))).append("]"));
            json.append("}");
        }
        json.append("\n  ],\n  \"sources\":[").append(result.getSources().values().stream().map(document ->
            "{\"id\":" + quote(document.getSourceId()) + ",\"text\":" + quote(document.getText()) + "}").collect(Collectors.joining(","))).append("]");
        json.append(",\n  \"frontendEvidence\":[").append(result.getFrontendEvidence().values().stream().map(LineageJsonRenderer::frontend).collect(Collectors.joining(","))).append("]");
        json.append(",\n  \"datasetVersions\":[").append(result.getDatasetVersions().stream().map(record ->
            "{\"statement\":" + quote(record.getStatement().getStatementId()) + ",\"objectId\":" + quote(record.getObjectId().getValue())
                + ",\"name\":" + record.getDisplayName().map(name -> quote(name.getDisplayName())).orElse("null")
                + ",\"version\":" + version(record.getState().getVersion()) + ",\"basis\":" + quote(record.getState().getBasis().name())
                + ",\"assumption\":" + quote(record.getAssumption().name()) + ",\"predecessors\":["
                + record.getState().getPredecessors().stream().map(id -> quote(id.getValue())).collect(Collectors.joining(",")) + "]}")
            .collect(Collectors.joining(","))).append("]");
        result.getBasis().ifPresent(basis -> json.append(",\n  \"metadataContext\":{\"sourceId\":")
            .append(quote(basis.getMetadataReads().getReadContext().getSourceId()))
            .append(",\"cluster\":").append(quote(basis.getMetadataReads().getReadContext().getClusterName()))
            .append(",\"snapshotId\":").append(basis.getMetadataReads().getReadContext().getSnapshotId().map(LineageJsonRenderer::quote).orElse("null"))
            .append(",\"consistency\":").append(quote(basis.getMetadataReads().getConsistency().name())).append("}"));
        json.append(",\n  \"metadataReads\":[");
        result.getBasis().ifPresent(basis -> json.append(basis.getMetadataReads().getRecords().stream().map(read ->
            "{\"name\":" + quote(read.getKey().getObjectName().getDisplayName()) + ",\"facet\":" + quote(read.getKey().getFacet().name())
                + ",\"status\":" + quote(read.getStatus().name()) + ",\"shape\":" + quote(read.getLookupShape().name())
                + ",\"origin\":" + quote(read.getProvenance().getOrigin().name())
                + ",\"fingerprint\":" + read.getProvenance().getContentFingerprint().map(LineageJsonRenderer::quote).orElse("null")
                + ",\"versionState\":" + quote(read.getMetadataVersion().getState().name())
                + ",\"version\":" + read.getMetadataVersion().getValue().map(LineageJsonRenderer::quote).orElse("null") + "}").collect(Collectors.joining(","))));
        return json.append("]\n}\n").toString();
    }

    private static String dependencies(List<Dependency> dependencies) {
        return dependencies.stream().map(dependency -> "{\"source\":" + endpoint(dependency.getSource())
            + ",\"target\":" + endpoint(dependency.getTarget()) + ",\"role\":" + quote(dependency.getRole().name())
            + ",\"precision\":" + quote(dependency.getPrecision().name()) + ",\"evidence\":" + quote(dependency.getEvidence().getId().getValue())
            + ",\"rule\":" + quote(dependency.getEvidence().getRuleId()) + ",\"parents\":["
            + dependency.getEvidence().getParentEvidence().stream().map(parent -> quote(parent.getValue())).collect(Collectors.joining(","))
            + "],\"operation\":" + quote(dependency.getEvidence().getOperationId().getValue())
            + ",\"sourceRef\":" + dependency.getEvidence().getSourceRef().map(LineageJsonRenderer::source).orElse("null") + "}")
            .collect(Collectors.joining(","));
    }

    static String endpoint(LineageEndpoint endpoint) {
        String identity = endpoint instanceof ColumnEndpoint ? ((ColumnEndpoint) endpoint).getObjectId().getValue() + ":" + ((ColumnEndpoint) endpoint).getFieldId().getValue()
            : endpoint instanceof RowSetEndpoint ? ((RowSetEndpoint) endpoint).getRelationId().getValue()
            : endpoint instanceof DirectFieldEndpoint ? ((DirectFieldEndpoint) endpoint).getOwner().getValue() + ":" + ((DirectFieldEndpoint) endpoint).getFieldId().getValue()
            : endpoint instanceof UnknownEndpoint ? ((UnknownEndpoint) endpoint).getDiagnostics().getDiagnostics().stream().map(diagnostic -> diagnostic.getCode()).collect(Collectors.joining(","))
            : endpoint instanceof UnknownDirectEndpoint ? ((UnknownDirectEndpoint) endpoint).getDisplayName().getDisplayName()
            : endpoint instanceof ConstantEndpoint ? ((ConstantEndpoint) endpoint).getValueRepresentation()
            : endpoint instanceof ParameterEndpoint ? ((ParameterEndpoint) endpoint).getNamespace() + ":" + ((ParameterEndpoint) endpoint).getName()
            : endpoint instanceof ExternalDatasetEndpoint ? ((ExternalDatasetEndpoint) endpoint).getDatasetIdentity()
            : endpoint instanceof SlotEndpoint ? ((SlotEndpoint) endpoint).getSlotId().getValue() : endpoint.getKind().name();
        String details = "";
        if (endpoint instanceof ColumnEndpoint || endpoint instanceof DirectFieldEndpoint) {
            List<String> path = endpoint instanceof ColumnEndpoint ? ((ColumnEndpoint) endpoint).getFieldPath() : ((DirectFieldEndpoint) endpoint).getFieldPath();
            details += ",\"fieldPath\":[" + path.stream().map(LineageJsonRenderer::quote).collect(Collectors.joining(",")) + "]";
            details += ",\"fieldId\":" + quote(endpoint instanceof ColumnEndpoint ? ((ColumnEndpoint) endpoint).getFieldId().getValue()
                : ((DirectFieldEndpoint) endpoint).getFieldId().getValue());
        }
        if (endpoint instanceof ColumnEndpoint) {
            details += ",\"objectId\":" + quote(((ColumnEndpoint) endpoint).getObjectId().getValue());
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            details += ",\"owner\":" + quote(((DirectFieldEndpoint) endpoint).getOwner().getValue()) + ",\"objectId\":"
                + ((DirectFieldEndpoint) endpoint).getObjectId().map(id -> quote(id.getValue())).orElse("null");
        }
        if (endpoint instanceof ExternalDatasetEndpoint) {
            details += ",\"sourceKind\":" + quote(((ExternalDatasetEndpoint) endpoint).getSourceKind());
        }
        if (endpoint instanceof ParameterEndpoint) {
            details += ",\"namespace\":" + quote(((ParameterEndpoint) endpoint).getNamespace()) + ",\"name\":" + quote(((ParameterEndpoint) endpoint).getName());
        }
        if (endpoint instanceof UnknownDirectEndpoint) {
            details += ",\"owner\":" + ((UnknownDirectEndpoint) endpoint).getOwner().map(id -> quote(id.getValue())).orElse("null");
            details += ",\"nameParts\":[" + ((UnknownDirectEndpoint) endpoint).getDisplayName().getParts().stream().map(LineageJsonRenderer::quote).collect(Collectors.joining(",")) + "]";
        }
        return "{\"kind\":" + quote(endpoint.getKind().name()) + ",\"identity\":" + quote(identity) + details + "}";
    }

    private static String frontend(ExpandedUnit unit) {
        return "{\"expandedSourceId\":" + quote(unit.getExpandedSourceId()) + ",\"variables\":["
            + unit.getVariableEvidence().stream().map(variable -> "{\"namespace\":" + quote(variable.getNamespace()) + ",\"name\":" + quote(variable.getName())
                + ",\"replacement\":" + (variable.getReplacement() == null ? "null" : quote(variable.getReplacement()))
                + ",\"resolution\":" + quote(variable.getResolution().name()) + ",\"sourceRef\":" + source(variable.getSourceRef()) + "}").collect(Collectors.joining(","))
            + "],\"sourceMap\":[" + unit.getSourceMap().getEntries().stream().map(entry -> "{\"kind\":" + quote(entry.getKind().name())
                + ",\"original\":" + span(entry.getOriginalSpan()) + ",\"expanded\":" + span(entry.getExpandedSpan()) + "}").collect(Collectors.joining(",")) + "]}";
    }

    private static String source(SourceRef ref) {
        return span(ref.getSpan());
    }

    private static String span(SourceSpan span) {
        return "{\"sourceId\":" + quote(span.getSourceId()) + ",\"start\":" + span.getStart() + ",\"end\":" + span.getEnd() + "}";
    }

    private static String readVersion(BoundRelation relation) {
        VersionRef version = relation instanceof Relations.Scan ? ((Relations.Scan) relation).getVersion() : ((Relations.DefinitionInstance) relation).getVersion();
        String object = relation instanceof Relations.Scan ? quote(((Relations.Scan) relation).getObjectId().getValue())
            : ((Relations.DefinitionInstance) relation).getPhysicalObject().map(id -> quote(id.getValue())).orElse("null");
        return "{\"relation\":" + quote(relation.getId().getValue()) + ",\"objectId\":" + object + ",\"version\":" + version(version) + "}";
    }

    private static String version(VersionRef version) {
        return "{\"state\":" + quote(version.getState().name()) + ",\"id\":" + version.getId().map(id -> quote(id.getValue())).orElse("null")
            + ",\"diagnostic\":" + version.getDiagnosticCode().map(LineageJsonRenderer::quote).orElse("null") + "}";
    }

    private static String schema(OutputSchema schema) {
        return "{\"state\":" + quote(schema.getState().name()) + ",\"segments\":[" + schema.getSegments().stream().map(segment -> {
            if (segment instanceof KnownOutputSegment) {
                return "{\"kind\":\"KNOWN\",\"slots\":[" + ((KnownOutputSegment) segment).getSlots().stream().map(LineageJsonRenderer::slot).collect(Collectors.joining(",")) + "]}";
            }
            UnknownOutputSegment unknown = (UnknownOutputSegment) segment;
            return "{\"kind\":\"UNKNOWN\",\"id\":" + quote(unknown.getId().getValue()) + ",\"diagnostic\":" + quote(unknown.getDiagnosticCode()) + "}";
        }).collect(Collectors.joining(",")) + "]}";
    }

    private static String slot(OutputSlot slot) {
        return "{\"id\":" + quote(slot.getId().getValue()) + ",\"name\":" + quote(slot.getDisplayName())
            + ",\"fieldId\":" + slot.getFieldIdentity().map(field -> quote(field.getId().getValue())).orElse("null")
            + ",\"typeKind\":" + quote(slot.getType().getKind().name()) + ",\"type\":" + quote(slot.getType().getDisplayName()) + "}";
    }

    private static String fieldHandles(BoundAnalysisUnit unit) {
        java.util.ArrayDeque<BoundExpression> remaining = new java.util.ArrayDeque<>();
        java.util.Set<com.panfan.hivelineage.v2.ir.identity.NodeId> visited = new java.util.HashSet<>();
        java.util.List<String> declarations = new java.util.ArrayList<>();
        unit.getRelations().forEach(relation -> remaining.addAll(IrTraversal.expressions(relation)));
        for (BoundWrite write : unit.getWrites()) {
            write.getAssignments().forEach(assignment -> remaining.add(assignment.getExpression()));
            write.getPartitionScope().getAssignments().forEach(assignment -> remaining.add(assignment.getExpression()));
            write.getCondition().ifPresent(remaining::add);
        }
        while (!remaining.isEmpty()) {
            BoundExpression expression = remaining.removeFirst();
            if (!visited.add(expression.getId())) {
                continue;
            }
            if (expression instanceof QualifiedFieldHandle || expression instanceof CorrelatedQualifiedFieldHandle) {
                QualifiedFieldHandle local = expression instanceof QualifiedFieldHandle ? (QualifiedFieldHandle) expression : null;
                CorrelatedQualifiedFieldHandle correlated = expression instanceof CorrelatedQualifiedFieldHandle ? (CorrelatedQualifiedFieldHandle) expression : null;
                declarations.add("{\"expression\":" + quote(expression.getId().getValue()) + ",\"owner\":"
                    + quote(local == null ? correlated.getOwner().getValue() : local.getOwner().getValue())
                    + ",\"fieldId\":" + quote(local == null ? correlated.getFieldIdentity().getId().getValue() : local.getFieldIdentity().getId().getValue())
                    + ",\"name\":" + quote(local == null ? correlated.getFieldIdentity().getDisplayName() : local.getFieldIdentity().getDisplayName())
                    + ",\"existence\":" + quote(local == null ? correlated.getExistence().name() : local.getExistence().name()) + "}");
            }
            remaining.addAll(IrTraversal.children(expression));
        }
        return String.join(",", declarations);
    }

    static String quote(String text) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c < 32) {
                escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }
}
