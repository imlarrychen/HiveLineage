package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import com.panfan.hivelineage.v2.syntax.AssignmentSyntax;
import com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.MergeClauseSyntax;
import com.panfan.hivelineage.v2.syntax.MergeDeleteSyntax;
import com.panfan.hivelineage.v2.syntax.MergeInsertSyntax;
import com.panfan.hivelineage.v2.syntax.MergeStatementSyntax;
import com.panfan.hivelineage.v2.syntax.MergeUpdateSyntax;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class ModificationBinder {
    private final BindingWork work;
    private final ExpressionBinder expressions;

    ModificationBinder(BindingWork work) {
        this.work = work;
        this.expressions = new ExpressionBinder(work);
    }

    void update(UpdateStatementSyntax statement) {
        ReferenceScope scope = new ReferenceScope(work, null, new ScopeId(statement.getNodeId().getValue() + "/scope"),
            new QueryBlockId("root"), "", statement.getCtes());
        BoundRelation relation = new QueryBinder(work).from(statement.getTarget(), scope, false);
        ObjectId target = scope.sources.get(0).objectId;
        BoundExpression condition = statement.getWhere() == null || !work.requested(BindingRequirement.CONDITION_FIELDS)
            ? null : expressions.bind(statement.getWhere(), scope);
        write(new NodeId(statement.getNodeId().getValue() + "/write"), target, relation, BoundWrite.WriteMode.UPDATE,
            assignments(statement.getAssignments(), target, scope), condition);
    }

    void delete(DeleteStatementSyntax statement) {
        ReferenceScope scope = new ReferenceScope(work, null, new ScopeId(statement.getNodeId().getValue() + "/scope"),
            new QueryBlockId("root"), "", statement.getCtes());
        BoundRelation relation = new QueryBinder(work).from(statement.getTarget(), scope, false);
        ObjectId target = scope.sources.get(0).objectId;
        BoundExpression condition = statement.getWhere() == null || !work.requested(BindingRequirement.CONDITION_FIELDS)
            ? null : expressions.bind(statement.getWhere(), scope);
        write(new NodeId(statement.getNodeId().getValue() + "/write"), target, relation, BoundWrite.WriteMode.DELETE,
            Collections.<WriteAssignment>emptyList(), condition);
    }

    void merge(MergeStatementSyntax statement) {
        ReferenceScope scope = new ReferenceScope(work, null, new ScopeId(statement.getNodeId().getValue() + "/scope"),
            new QueryBlockId("root"), "", statement.getCtes());
        NamedFromSyntax targetSyntax = new NamedFromSyntax(new NodeId(statement.getNodeId().getValue() + "/target"),
            statement.getSourceRef(), statement.getBody().getTarget().getName(), statement.getBody().getTarget().getAlias(), null);
        QueryBinder queries = new QueryBinder(work);
        BoundRelation targetRelation = queries.from(targetSyntax, scope, false);
        ObjectId target = scope.sources.get(0).objectId;
        BoundRelation sourceRelation = queries.from(statement.getBody().getSource(), scope, false);
        BoundExpression match = work.requested(BindingRequirement.CONDITION_FIELDS) ? expressions.bind(statement.getBody().getCondition(), scope) : null;
        List<SlotMapping> mappings = BindingWork.mappings(targetRelation.getOutputSchema());
        mappings.addAll(BindingWork.mappings(sourceRelation.getOutputSchema()));
        BoundRelation relation = new Relations.Join(new NodeId(statement.getNodeId().getValue() + "/merge-input"),
            BindingWork.combined(targetRelation.getOutputSchema(), sourceRelation.getOutputSchema(), false),
            scope.block, statement.getSourceRef().getSpan().getStart(), statement.getSourceRef(), targetRelation.getId(),
            sourceRelation.getId(), Relations.JoinType.FULL, Relations.JoinForm.EXPLICIT, match, mappings,
            Collections.<Relations.JoinPredicate>emptyList());
        work.put(relation);
        int index = 0;
        for (MergeClauseSyntax clause : statement.getBody().getClauses()) {
            NodeId id = new NodeId(statement.getNodeId().getValue() + "/action-" + index++);
            BoundExpression condition = match;
            if (match != null) {
                condition = expressions.operator(new NodeId(id.getValue() + "/match-kind"), null,
                    "merge_" + clause.getMatchKind().name().toLowerCase(java.util.Locale.ROOT), Collections.singletonList(match));
            }
            if (clause.getCondition() != null && work.requested(BindingRequirement.CONDITION_FIELDS)) {
                BoundExpression predicate = expressions.bind(clause.getCondition(), scope);
                condition = condition == null ? predicate : expressions.operator(new NodeId(id.getValue() + "/condition"), null,
                    "and", Arrays.asList(condition, predicate));
            }
            if (clause.getAction() instanceof MergeUpdateSyntax) {
                write(id, target, relation, BoundWrite.WriteMode.UPDATE,
                    assignments(((MergeUpdateSyntax) clause.getAction()).getAssignments(), target, scope), condition);
            } else if (clause.getAction() instanceof MergeDeleteSyntax) {
                write(id, target, relation, BoundWrite.WriteMode.DELETE, Collections.<WriteAssignment>emptyList(), condition);
            } else {
                MergeInsertSyntax insert = (MergeInsertSyntax) clause.getAction();
                List<FieldIdentity> fields = new ArrayList<>();
                for (IdentifierPart column : insert.getTargetColumns()) {
                    fields.add(work.field(target, column.getValue()));
                }
                if (fields.isEmpty() && work.requested(BindingRequirement.SINK_MAPPING)) {
                    new ReferenceBinder(work).loadSchema(scope.sources.get(0), scope);
                    for (OutputSlot slot : BindingWork.slots(scope.sources.get(0).schema)) {
                        fields.add(slot.getFieldIdentity().get());
                    }
                }
                List<WriteAssignment> assignments = new ArrayList<>();
                List<SyntaxExpression> values = insert.getRow().getValues();
                if (work.requested(BindingRequirement.SINK_MAPPING) && fields.size() != values.size()) {
                    work.error("COLUMN_COUNT_MISMATCH", "MERGE INSERT width differs from target fields", statement.getSourceRef());
                    continue;
                }
                for (int value = 0; value < fields.size() && work.requested(BindingRequirement.SINK_MAPPING); value++) {
                    work.declareField(target, fields.get(value));
                    assignments.add(new WriteAssignment(fields.get(value), expressions.bind(values.get(value), scope)));
                }
                write(id, target, relation, BoundWrite.WriteMode.APPEND, assignments, condition);
            }
        }
    }

    private List<WriteAssignment> assignments(List<AssignmentSyntax> syntax, ObjectId target, ReferenceScope scope) {
        List<WriteAssignment> result = new ArrayList<>();
        if (!work.requested(BindingRequirement.SINK_MAPPING)) {
            return result;
        }
        for (AssignmentSyntax assignment : syntax) {
            FieldIdentity field = work.field(target, SemanticNames.last(assignment.getTarget()));
            work.declareField(target, field);
            result.add(new WriteAssignment(field, expressions.bind(assignment.getValue(), scope)));
        }
        return result;
    }

    private void write(NodeId id, ObjectId target, BoundRelation source, BoundWrite.WriteMode mode,
                       List<WriteAssignment> assignments, BoundExpression condition) {
        VersionRef before = work.requested(BindingRequirement.DATASET_VERSIONS)
            ? work.context.getSession().getDatasetVersions().containsKey(target)
                ? work.context.getSession().getDatasetVersions().get(target).getVersion()
                : VersionRef.known(new DatasetVersionId(target.getValue() + "/session/"
                    + work.context.getSession().getInitialContext().getSessionId() + "/script-entry")) : VersionRef.notRequested();
        VersionRef after = work.requested(BindingRequirement.DATASET_VERSIONS)
            ? VersionRef.known(new DatasetVersionId(target.getValue() + "/session/"
                + work.context.getSession().getInitialContext().getSessionId() + "/write/" + id.getValue()))
            : VersionRef.notRequested();
        work.writes.add(new BoundWrite(id, target, before, after, mode, assignments,
            new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.<PartitionScope.Assignment>emptyList()), condition,
            work.requested(BindingRequirement.SINK_MAPPING) ? MappingCoverage.COMPLETE : MappingCoverage.NOT_REQUESTED,
            Collections.<UnknownMapping>emptyList(), source.getId()));
        work.queryResults.add(new QueryResult(source.getId(), source.getQueryBlockId(), source.getOutputSchema(), QueryResult.Role.MAIN));
    }
}
