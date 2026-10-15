package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.result.WarningCode;
import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.analysis.UnknownEndpoint;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.StatementPublicationPolicy;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.export.SourceRenderer;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.export.BoundExpressionFormatter;
import com.panfan.hivelineage.v2.export.BoundInputObjectProjector;
import com.panfan.hivelineage.v2.export.ColumnDerivationClassifier;
import com.panfan.hivelineage.v2.export.ColumnDerivationKind;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LegacyStatementProjector {
    private final SourceRenderer renderer;
    private final Optional<AnalysisContract> contract;
    private final List<ProjectionLimitation> limitations;

    LegacyStatementProjector(SourceRenderer renderer, Optional<AnalysisContract> contract, List<ProjectionLimitation> limitations) {
        this.renderer = renderer;
        this.contract = contract;
        this.limitations = limitations;
    }

    StatementLineageResult project(StatementAnalysisResult statement) {
        StatementIdentity identity = statement.getIdentity();
        TaskIdentity task = identity.getTask();
        List<LineageWarning> warnings = warnings(statement);
        unknownLimitations(statement, warnings);
        List<TableLineage> tables = new ArrayList<TableLineage>();
        List<ColumnLineage> columns = new ArrayList<ColumnLineage>();
        List<JoinCondition> joins = new ArrayList<JoinCondition>();
        List<DataObjectRef> inputs = new ArrayList<DataObjectRef>();
        DataObjectRef output = null;
        if (statement.getBoundUnit().isPresent()) {
            BoundAnalysisUnit unit = statement.getBoundUnit().get();
            BoundNameIndex names = new BoundNameIndex(unit);
            boolean includeTemporary = contract.map(AnalysisContract::isIncludeTemporaryLineage).orElse(false);
            BoundExpressionFormatter expressions = new BoundExpressionFormatter(unit, names, renderer,
                    object -> names.view(object) || includeTemporary && names.temporary(object));
            inputs = inputs(statement, names);
            output = output(statement, unit, names, warnings);
            if (statement.isFormalLineageProduced() && statement.getLineage().isPresent()) {
                for (BoundWrite write : unit.getWrites()) {
                    if (tableMode() && !inputs(statement, names, Optional.of(write)).isEmpty()) {
                        tables.add(new TableLineage(names.object(write.getTargetObject()).map(DataObjectRef::new).orElse(null),
                                inputs(statement, names, Optional.of(write)), statement.getStatementType(),
                                identity.getStatementIndex(), throughTemporary(statement, write, names)));
                    }
                    if (columnMode()) {
                        columns.addAll(columns(statement, write, names, expressions));
                    }
                }
            }
            if (statement.isFormalLineageProduced() && statement.getLineage().isPresent()) {
                LegacyBoundaryProjector boundaries = new LegacyBoundaryProjector(statement, names, expressions,
                        contract.map(AnalysisContract::isIncludeTemporaryLineage).orElse(false));
                if (tableMode()) {
                    tables.addAll(0, boundaries.tables());
                }
                if (columnMode()) {
                    columns.addAll(0, boundaries.columns());
                }
            }
            if (statement.getJoins().isPresent()) {
                LegacyJoinProjector projector = new LegacyJoinProjector(names, renderer, expressions);
                for (JoinFact fact : statement.getJoins().get().getJoins()) {
                    joins.add(projector.project(fact));
                }
            }
        }
        return StatementLineageResult.builder().workflowId(task.getWorkflowId()).taskId(task.getTaskId()).taskVersion(task.getTaskVersion())
                .statementIndex(identity.getStatementIndex()).statementId(identity.getStatementId()).statementType(statementType(statement))
                .rawSql(statement.getRawSql().getText()).normalizedSql(currentApi()
                        ? LegacySqlPresentation.normalized(statement.getNormalizedSql().getText()) : statement.getNormalizedSql().getText())
                .parseStatusCode(status(statement)).formalLineageProduced(statement.isFormalLineageProduced())
                .outputObject(output).inputObjects(inputs).parseMode(mode()).skipReason(statement.getSkipReason().orElse(null))
                .failureCode(failureCode(statement)).failureMessage(statement.getFailureMessage().orElse(null))
                .tableLineages(tables).columnLineages(columns).joinConditions(joins).warnings(warnings).build();
    }

    private void unknownLimitations(StatementAnalysisResult statement, List<LineageWarning> warnings) {
        if (!statement.getLineage().isPresent()) {
            return;
        }
        for (Dependency dependency : statement.getLineage().get().getWriteDependencies()) {
            boolean fieldUncertainty = dependency.getTarget() instanceof ColumnEndpoint
                    || "write.mapping.unknown".equals(dependency.getEvidence().getRuleId());
            if (dependency.getSource() instanceof UnknownEndpoint && fieldUncertainty) {
                String message = "Unknown source dependencies remain explicit in V2; the legacy field list contains proven references only";
                limitations.add(new ProjectionLimitation("UNKNOWN_DEPENDENCY_NOT_EXPRESSIBLE", message,
                        Optional.of(statement.getIdentity())));
                boolean diagnosed = warnings.stream().anyMatch(warning -> warning.getWarningCode() == WarningCode.UNKNOWN_COLUMN_LINEAGE);
                if (!diagnosed) {
                    warnings.add(new LineageWarning(WarningCode.UNKNOWN_COLUMN_LINEAGE, message, statement.getIdentity().getStatementIndex()));
                }
                return;
            }
        }
    }

    private DataObjectRef output(StatementAnalysisResult statement, BoundAnalysisUnit unit, BoundNameIndex names,
                                 List<LineageWarning> warnings) {
        Set<ObjectId> targets = new LinkedHashSet<ObjectId>();
        for (BoundWrite write : unit.getWrites()) {
            targets.add(write.getTargetObject());
        }
        if (targets.size() > 1) {
            String message = "The legacy outputObject holds one target; all writes are retained in table/column lists and the V2 result";
            limitations.add(new ProjectionLimitation("MULTIPLE_TARGET_OBJECTS", message, Optional.of(statement.getIdentity())));
            warnings.add(new LineageWarning(WarningCode.UNSUPPORTED_STATEMENT, "MULTIPLE_TARGET_OBJECTS: " + message,
                    statement.getIdentity().getStatementIndex()));
        }
        return targets.isEmpty() ? null : names.object(targets.iterator().next()).map(DataObjectRef::new).orElse(null);
    }

    private List<DataObjectRef> inputs(StatementAnalysisResult statement, BoundNameIndex names) {
        return inputs(statement, names, Optional.<BoundWrite>empty());
    }

    private List<DataObjectRef> inputs(StatementAnalysisResult statement, BoundNameIndex names, Optional<BoundWrite> write) {
        List<DataObjectRef> result = new ArrayList<DataObjectRef>();
        BoundInputObjectProjector inputs = new BoundInputObjectProjector(statement.getBoundUnit().get(), names,
                contract.map(AnalysisContract::isIncludeTemporaryLineage).orElse(false));
        for (com.panfan.hivelineage.v2.common.QualifiedName input : inputs.inputs(write)) {
            result.add(new DataObjectRef(input.getDisplayName()));
        }
        return result;
    }

    private List<ColumnLineage> columns(StatementAnalysisResult statement, BoundWrite write, BoundNameIndex names,
                                        BoundExpressionFormatter expressions) {
        List<ColumnLineage> result = new ArrayList<ColumnLineage>();
        LegacyColumnSourceSelector selector = new LegacyColumnSourceSelector(statement.getBoundUnit().get(), names,
                contract.map(AnalysisContract::isIncludeTemporaryLineage).orElse(false));
        Map<FieldId, WriteAssignment> assignments = new LinkedHashMap<FieldId, WriteAssignment>();
        for (WriteAssignment assignment : write.getAssignments()) {
            assignments.put(assignment.getTargetField().getId(), assignment);
        }
        for (WriteAssignment assignment : orderedAssignments(write, assignments)) {
            if (missingMetadataWidthPlaceholder(statement, assignment)) {
                limitations.add(new ProjectionLimitation("UNKNOWN_SOURCE_WIDTH_TARGETS_NOT_PUBLISHED",
                        "Legacy output omits target placeholders for a wildcard whose source metadata is unavailable; V2 retains them",
                        Optional.of(statement.getIdentity())));
                continue;
            }
            List<Dependency> dependencies = dependencies(statement, write, assignment.getTargetField());
            List<Dependency> selected = selector.select(dependencies, assignment.getExpression(), write);
            Set<String> sources = new LinkedHashSet<String>();
            Set<String> objects = new LinkedHashSet<String>();
            for (Dependency dependency : selected) {
                if (include(dependency, names)) {
                    if (dependency.getRole() != DependencyRole.ROWSET) {
                        names.column(dependency.getSource(), false).ifPresent(sources::add);
                    }
                    names.physicalObject(dependency.getSource()).ifPresent(objects::add);
                }
            }
            if (currentApi()) {
                objects.clear();
                inputs(statement, names, Optional.of(write)).forEach(input -> objects.add(input.getQualifiedName()));
            }
            boolean atomicSetUnknown = currentApi() && expressions.hasSetProvenance(assignment.getExpression())
                    && dependencies.stream().anyMatch(dependency -> dependency.getRole() == DependencyRole.VALUE
                            && dependency.getSource() instanceof UnknownEndpoint);
            if (atomicSetUnknown) {
                sources.clear();
                objects.clear();
                limitations.add(new ProjectionLimitation("ATOMIC_SET_MAPPING",
                        "Legacy set mapping omits partial source fields; V2 retains the proven and unknown dependencies",
                        Optional.of(statement.getIdentity())));
            }
            List<ColumnRef> sourceColumns = new ArrayList<ColumnRef>();
            for (String source : sources) {
                sourceColumns.add(new ColumnRef(source));
            }
            List<DataObjectRef> sourceObjects = new ArrayList<DataObjectRef>();
            for (String source : objects) {
                sourceObjects.add(new DataObjectRef(source));
            }
            Optional<String> expression = expressions.format(assignment.getExpression());
            ColumnDerivationKind kind = ColumnDerivationClassifier.classify(expressions, assignment.getExpression(), dependencies);
            LineageType derivation = kind == ColumnDerivationKind.UNKNOWN ? LineageType.DERIVED : LineageType.valueOf(kind.name());
            result.add(ColumnLineage.builder().targetColumn(names.column(write.getTargetObject(), assignment.getTargetField())
                    .map(ColumnRef::new).orElse(null)).sourceColumns(sourceColumns).sourceObjects(sourceObjects)
                    .expression(expression.orElse(null)).expressionExtracted(expressions.extracted(assignment.getExpression()))
                    .lineageType(derivation)
                    .functionNames(expressions.functionsWithOccurrences(assignment.getExpression()))
                    .complexExpression(derivation != LineageType.CONSTANT && expressions.complex(assignment.getExpression()))
                    .statementIndex(statement.getIdentity().getStatementIndex()).build());
        }
        return result;
    }

    private boolean missingMetadataWidthPlaceholder(StatementAnalysisResult statement, WriteAssignment assignment) {
        if (!currentApi() || !(assignment.getExpression() instanceof Expressions.UnknownExpression)
                || !"SOURCE_WIDTH_UNKNOWN".equals(((Expressions.UnknownExpression) assignment.getExpression()).getDiagnosticCode())) {
            return false;
        }
        boolean unknownScan = statement.getBoundUnit().get().getRelations().stream().anyMatch(relation ->
                relation instanceof Relations.Scan && relation.getOutputSchema().getState() == SchemaState.UNKNOWN);
        if (!unknownScan) {
            return false;
        }
        return statement.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic ->
                "METADATA_NOT_FOUND".equals(diagnostic.getCode()) || "METADATA_CAPABILITY_MISSING".equals(diagnostic.getCode()));
    }

    private int status(StatementAnalysisResult statement) {
        if (statement.getStatus() == StatementStatus.PARTIAL_SUCCESS
                && contract.map(value -> value.getPublicationPolicy() == StatementPublicationPolicy.CURRENT_API).orElse(false)) {
            return StatementStatus.SUCCESS.getCode();
        }
        return statement.getStatus().getCode();
    }

    private boolean currentApi() {
        return contract.map(value -> value.getPublicationPolicy() == StatementPublicationPolicy.CURRENT_API).orElse(false);
    }

    private String failureCode(StatementAnalysisResult statement) {
        String code = statement.getFailureCode().orElse(null);
        return currentApi() && "SQL_PARSE_FAILED".equals(code) ? "SQL_PARSE_ERROR" : code;
    }

    private String statementType(StatementAnalysisResult statement) {
        return currentApi() && statement.getStatus() == StatementStatus.FAILED
                && "SQL_PARSE_FAILED".equals(statement.getFailureCode().orElse(null)) ? "UNKNOWN" : statement.getStatementType();
    }

    private List<WriteAssignment> orderedAssignments(BoundWrite write, Map<FieldId, WriteAssignment> assignments) {
        Set<FieldId> staticFields = new LinkedHashSet<FieldId>();
        for (PartitionScope.Assignment partition : write.getPartitionScope().getAssignments()) {
            if (partition.getKind() == PartitionScope.AssignmentKind.STATIC) {
                staticFields.add(partition.getField().getId());
            }
        }
        List<WriteAssignment> result = new ArrayList<WriteAssignment>();
        for (WriteAssignment assignment : assignments.values()) {
            if (!staticFields.contains(assignment.getTargetField().getId())) {
                result.add(assignment);
            }
        }
        for (FieldId field : staticFields) {
            if (assignments.containsKey(field)) {
                result.add(assignments.get(field));
            }
        }
        return result;
    }

    private List<Dependency> dependencies(StatementAnalysisResult statement, BoundWrite write, FieldIdentity field) {
        List<Dependency> result = new ArrayList<Dependency>();
        for (Dependency dependency : statement.getLineage().get().getWriteDependencies()) {
            LineageEndpoint target = dependency.getTarget();
            if (target instanceof ColumnEndpoint && ((ColumnEndpoint) target).getObjectId().equals(write.getTargetObject())
                    && ((ColumnEndpoint) target).getFieldId().equals(field.getId())
                    && dependency.getEvidence().getOperationId().equals(write.getId())) {
                result.add(dependency);
            }
        }
        return result;
    }

    private boolean include(Dependency dependency, BoundNameIndex names) {
        if (dependency.getRole() == DependencyRole.DEFINITION) {
            return !names.temporary(dependency.getSource()) || contract.map(AnalysisContract::isIncludeTemporaryLineage).orElse(false);
        }
        return dependency.getRole() == DependencyRole.VALUE || dependency.getRole() == DependencyRole.CONDITION
                || dependency.getRole() == DependencyRole.ROWSET;
    }

    private boolean throughTemporary(StatementAnalysisResult statement, BoundWrite write, BoundNameIndex names) {
        for (Dependency dependency : statement.getLineage().get().getWriteDependencies()) {
            if (dependency.getEvidence().getOperationId().equals(write.getId())
                    && dependency.getRole() == DependencyRole.DEFINITION
                    && names.endpointObject(dependency.getSource()).map(names::temporary).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private List<LineageWarning> warnings(StatementAnalysisResult statement) {
        List<LineageWarning> result = new ArrayList<LineageWarning>();
        Set<String> seen = new LinkedHashSet<String>();
        boolean ddlAudit = currentApi() && statement.getStatus() == StatementStatus.IGNORED
                && statement.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "INVALID_DDL".equals(diagnostic.getCode()));
        for (Diagnostic diagnostic : statement.getDiagnostics().getDiagnostics()) {
            if (ddlAudit && !"INVALID_DDL".equals(diagnostic.getCode())) {
                continue;
            }
            if (currentApi() && ("OUTPUT_SCHEMA_UNKNOWN".equals(diagnostic.getCode())
                    || "INVALID_USE_DATABASE".equals(diagnostic.getCode()))) {
                continue;
            }
            if (seen.add(diagnostic.getCode() + ":" + diagnostic.getMessage())) {
                result.add(new LineageWarning(warningCode(diagnostic.getCode()), diagnostic.getCode() + ": " + diagnostic.getMessage(),
                        statement.getIdentity().getStatementIndex()));
            }
        }
        return result;
    }

    private WarningCode warningCode(String code) {
        if ("VIEW_DEFINITION_NOT_FOUND".equals(code)) {
            return WarningCode.VIEW_METADATA_NOT_FOUND;
        }
        try {
            return WarningCode.valueOf(code);
        } catch (IllegalArgumentException ignored) {
            if (code.contains("METADATA") || code.contains("SCHEMA_UNKNOWN")) {
                return WarningCode.METADATA_NOT_FOUND;
            }
            if (code.contains("VIEW")) {
                return WarningCode.VIEW_EXPANSION_FAILED;
            }
            return WarningCode.UNKNOWN_COLUMN_LINEAGE;
        }
    }

    private boolean tableMode() {
        return !contract.isPresent() || contract.get().getCapabilities().supports(AnalysisCapability.TABLE_LINEAGE);
    }

    private boolean columnMode() {
        return !contract.isPresent() || contract.get().getCapabilities().supports(AnalysisCapability.COLUMN_LINEAGE);
    }

    private LineageParseMode mode() {
        if (tableMode() && columnMode()) {
            return LineageParseMode.TABLE_AND_COLUMN;
        }
        return columnMode() ? LineageParseMode.COLUMN_ONLY : LineageParseMode.TABLE_ONLY;
    }
}
