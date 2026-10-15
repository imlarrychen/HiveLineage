package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.DefinitionPolicy;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InsertBranchSyntax;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetKindSyntax;
import com.panfan.hivelineage.v2.syntax.InsertTargetSyntax;
import com.panfan.hivelineage.v2.syntax.PartitionEntrySyntax;
import com.panfan.hivelineage.v2.syntax.QueryInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.LoadDataStatementSyntax;
import com.panfan.hivelineage.v2.syntax.LoadDataBodySyntax;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StatementBinder {
    private final BindingWork work;
    private final SinkPlanner sinks;
    private final EffectBinder effects;

    StatementBinder(BindingWork work, SinkPlanner sinks, EffectBinder effects) {
        this.work = work;
        this.sinks = sinks;
        this.effects = effects;
    }

    void bind(SyntaxStatement statement) {
        EffectBindingResult effectResult = effects.bind(statement, work.context, work.demand);
        work.effects.addAll(effectResult.getEffects());
        work.diagnostics.addAll(effectResult.getDiagnostics().getDiagnostics());
        for (com.panfan.hivelineage.v2.ir.statement.BoundEffect effect : effectResult.getEffects()) {
            if (effect.getCertainty() != Effects.Certainty.CERTAIN) {
                work.partial = true;
            }
        }
        for (com.panfan.hivelineage.v2.common.Diagnostic diagnostic : effectResult.getDiagnostics().getDiagnostics()) {
            if ("CREATE_LIKE_SCHEMA_UNKNOWN".equals(diagnostic.getCode())) {
                work.partial = true;
            } else {
                work.failed = true;
            }
        }
        if (statement instanceof QueryStatementSyntax) {
            QueryStatementSyntax query = (QueryStatementSyntax) statement;
            if (!work.requested(BindingRequirement.CONDITION_FIELDS) && !work.requested(BindingRequirement.OUTPUT_SCHEMA)) {
                for (CteSyntax cte : query.getQuery().getCtes()) {
                    NodeId relationId = new NodeId(cte.getNodeId().getValue() + "/deferred-definition");
                    BoundRelation deferred = new com.panfan.hivelineage.v2.ir.relation.Relations.UnknownRelation(
                        relationId, OutputSchema.notRequested(), cte.getQuery().getQueryBlockId(),
                        cte.getSourceRef().getSpan().getStart(), cte.getSourceRef(),
                        Collections.<NodeId>emptyList(), "DEFINITION_NOT_REQUESTED");
                    work.put(deferred);
                    work.cteRoots.put(cte.getNodeId(), deferred);
                }
            } else {
            new QueryBinder(work).query(query.getQuery(), null, work.requested(BindingRequirement.OUTPUT_SCHEMA),
                QueryResult.Role.MAIN, "");
            }
            if (work.context.getContract().getDefinitionPolicy() == DefinitionPolicy.CURRENT_API_CTE_REUSE) {
                for (CteSyntax cte : query.getQuery().getCtes()) {
                    BoundRelation root = work.cteRoots.get(cte.getNodeId());
                    if (root != null) {
                        work.effects.add(new Effects.RegisterDefinition(new NodeId(cte.getNodeId().getValue() + "/register"),
                            Effects.Certainty.CERTAIN, always(), new DefinitionId(cte.getNodeId().getValue() + "/definition"),
                            new QualifiedName(Collections.singletonList(SemanticNames.normalize(cte.getName().getValue()))),
                            root.getOutputSchema(), root.getId(), null));
                    }
                }
            }
        } else if (statement instanceof InsertStatementSyntax) {
            insert((InsertStatementSyntax) statement);
        } else if (statement instanceof DefinitionStatementSyntax) {
            definition((DefinitionStatementSyntax) statement);
        } else if (statement instanceof LoadDataStatementSyntax) {
            load((LoadDataStatementSyntax) statement);
        } else if (statement instanceof com.panfan.hivelineage.v2.syntax.MergeStatementSyntax) {
            new ModificationBinder(work).merge((com.panfan.hivelineage.v2.syntax.MergeStatementSyntax) statement);
        } else if (statement instanceof com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax) {
            new ModificationBinder(work).update((com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax) statement);
        } else if (statement instanceof com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax) {
            new ModificationBinder(work).delete((com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax) statement);
        }
    }

    private void load(LoadDataStatementSyntax statement) {
        if (!work.context.getContract().getCapabilities().supports(AnalysisCapability.EXTERNAL_DATA_LINEAGE)) {
            work.unsupported = true;
            work.warn("EXTERNAL_DATA_NOT_REQUESTED", "LOAD DATA requires the explicit external data lineage capability", statement.getSourceRef());
            return;
        }
        LoadDataBodySyntax body = statement.getBody();
        if (!(body.getPath() instanceof LiteralSyntax)
            || ((LiteralSyntax) body.getPath()).getLiteralKind() != LiteralKind.STRING
            || ((LiteralSyntax) body.getPath()).getDecodedValue() == null) {
            work.unsupported = true;
            work.warn("EXTERNAL_DATA_PATH_UNKNOWN", "LOAD DATA path must be a declared string literal", statement.getSourceRef());
            return;
        }
        NodeId relationId = new NodeId(statement.getNodeId().getValue() + "/external-data");
        QueryBlockId block = new QueryBlockId(statement.getNodeId().getValue() + "/load");
        ExternalDatasetRelation input = new ExternalDatasetRelation(relationId,
            BindingWork.unknown(new NodeId(relationId.getValue() + "/width"), "EXTERNAL_DATA_SCHEMA_UNKNOWN"),
            block, statement.getSourceRef().getSpan().getStart(), body.getPath().getSourceRef(),
            ((LiteralSyntax) body.getPath()).getDecodedValue(), body.isLocal()
                ? ExternalDatasetRelation.SourceKind.LOCAL_PATH : ExternalDatasetRelation.SourceKind.DISTRIBUTED_PATH);
        work.put(input);
        CatalogObjectResolver.Resolution resolved = new CatalogObjectResolver(work.context).resolve(
            body.getTarget(), work.context.getSession().getCurrentDatabase(), true);
        resolved.requireAvailable();
        QualifiedName name = resolved.name;
        ObjectId object = resolved.object;
        NodeId writeId = new NodeId(statement.getNodeId().getValue() + "/write");
        OutputSchema schema = targetSchema(name, object, writeId, Collections.<IdentifierPart>emptyList(), null);
        ReferenceScope scope = new ReferenceScope(work, null, new ScopeId(statement.getNodeId().getValue() + "/load-scope"),
            block, "", Collections.<CteSyntax>emptyList());
        InsertTargetSyntax target = new InsertTargetSyntax(InsertTargetKindSyntax.TABLE, body.getTarget(), null,
            Collections.<IdentifierPart>emptyList(), body.getPartitions());
        for (PartitionEntrySyntax entry : body.getPartitions()) {
            if (entry.getStaticValue() == null) {
                work.unsupported = true;
                work.warn("LOAD_DYNAMIC_PARTITION_UNSUPPORTED", "LOAD DATA cannot prove a dynamic partition expression", statement.getSourceRef());
                return;
            }
            work.declareField(object, work.field(object, entry.getColumn().getValue()));
        }
        PartitionScope partition = partitions(target, object, input, scope);
        accept(sinks.plan(input, new SinkBindingRequest(writeId, object, schema, Collections.<FieldIdentity>emptyList(),
            partition, body.isOverwrite() ? BoundWrite.WriteMode.OVERWRITE : BoundWrite.WriteMode.APPEND,
            before(object), after(object, writeId), work.context.getContract().getStarMappingPolicy())));
    }

    private void insert(InsertStatementSyntax statement) {
        ReferenceScope ctes = new ReferenceScope(work, null, new ScopeId(statement.getNodeId().getValue() + "/context"),
            new QueryBlockId("root"), "", statement.getCtes());
        for (InsertBranchSyntax branch : statement.getBranches()) {
            if (!(branch.getSource() instanceof QueryInsertSourceSyntax)
                || branch.getTarget().getKind() != InsertTargetKindSyntax.TABLE) {
                continue;
            }
            BoundRelation query = new QueryBinder(work).query(((QueryInsertSourceSyntax) branch.getSource()).getQuery(), ctes,
                work.requested(BindingRequirement.OUTPUT_SCHEMA), QueryResult.Role.MAIN, "", statement.getSharedFrom());
            CatalogObjectResolver.Resolution resolved = new CatalogObjectResolver(work.context).resolve(
                branch.getTarget().getTableName(), ctes.database, true);
            resolved.requireAvailable();
            QualifiedName name = resolved.name;
            ObjectId object = resolved.object;
            NodeId writeId = new NodeId(branch.getNodeId().getValue() + "/write");
            OutputSchema schema = targetSchema(name, object, writeId, branch.getTarget().getTargetColumns(), null);
            PartitionScope partition = partitions(branch.getTarget(), object, query, ctes);
            BoundWrite.WriteMode mode = branch.getMode() == InsertModeSyntax.OVERWRITE ? BoundWrite.WriteMode.OVERWRITE : BoundWrite.WriteMode.APPEND;
            SinkPlanningResult result = sinks.plan(query, new SinkBindingRequest(writeId, object, schema,
                explicitFields(object, branch.getTarget().getTargetColumns()), partition, mode, before(object), after(object, writeId),
                work.context.getContract().getStarMappingPolicy(), containsStar(((QueryInsertSourceSyntax) branch.getSource()).getQuery())));
            accept(result);
        }
    }

    private void definition(DefinitionStatementSyntax statement) {
        DefinitionBodySyntax definition = statement.getDefinition();
        if (definition.getQuery() == null) {
            return;
        }
        boolean temporary = definition.getOptions().isTemporary();
        QualifiedName name = SemanticNames.qualify(definition.getName(), work.context, temporary);
        ObjectId object = work.objectId(name);
        Effects.Certainty certainty = Effects.Certainty.CERTAIN;
        Effects.Precondition precondition = always();
        if (definition.getOptions().isIfNotExists() && (temporary
            || work.context.getContract().getCapabilities().supports(AnalysisCapability.CATALOG_SIMULATION)
            || work.context.getContract().getCapabilities().supports(AnalysisCapability.DATASET_VERSION_SIMULATION))) {
            LookupStatus existing = new ObjectExistenceResolver(work.context).lookup(name, object, temporary);
            if (existing == LookupStatus.FOUND) {
                return;
            }
            if (existing != LookupStatus.NOT_FOUND) {
                certainty = Effects.Certainty.CONDITIONAL;
                precondition = new Effects.Precondition(Effects.Precondition.Kind.OBJECT_ABSENT, object);
                work.partial = true;
            }
        }
        boolean view = definition.getKind() == DefinitionKindSyntax.CREATE_VIEW || definition.getKind() == DefinitionKindSyntax.ALTER_VIEW;
        BoundRelation query = new QueryBinder(work).query(definition.getQuery(), null,
            work.requested(BindingRequirement.OUTPUT_SCHEMA), QueryResult.Role.MAIN, "");
        NodeId writeId = new NodeId(statement.getNodeId().getValue() + "/write");
        List<IdentifierPart> declared = new ArrayList<>();
        for (ColumnDefinitionSyntax column : definition.getSchema().getColumns()) {
            declared.add(column.getName());
        }
        OutputSchema schema = targetSchema(name, object, writeId, declared, query.getOutputSchema());
        BoundObjectReference.ObjectKind kind = temporary ? BoundObjectReference.ObjectKind.TEMPORARY
            : view ? BoundObjectReference.ObjectKind.VIEW : BoundObjectReference.ObjectKind.TABLE;
        List<FieldIdentity> fields = new ArrayList<>();
        for (OutputSlot slot : BindingWork.slots(schema)) {
            fields.add(slot.getFieldIdentity().get());
        }
        DefinitionId definitionId = new DefinitionId(statement.getNodeId().getValue() + "/definition");
        work.declare(object, name, kind, schema.getState(), fields, definitionId);
        accept(sinks.plan(query, new SinkBindingRequest(writeId, object, schema, explicitFields(object, declared),
            new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.<PartitionScope.Assignment>emptyList()),
            BoundWrite.WriteMode.CREATE_AS, before(object), after(object, writeId), work.context.getContract().getStarMappingPolicy())));
        work.effects.add(new Effects.CreateObject(new NodeId(statement.getNodeId().getValue() + "/create"), certainty,
            precondition, new Effects.ObjectShape(object, name, temporary ? Effects.ObjectKind.TEMPORARY_TABLE
                : view ? Effects.ObjectKind.VIEW : Effects.ObjectKind.TABLE, schema, after(object, writeId),
                temporary ? SemanticNames.temporaryLookupNames(definition.getName(), work.context) : Collections.singletonList(name))));
        work.effects.add(new Effects.RegisterDefinition(new NodeId(statement.getNodeId().getValue() + "/register"),
            certainty, precondition, definitionId, name, schema, query.getId(), object));
    }

    private boolean containsStar(com.panfan.hivelineage.v2.syntax.SyntaxQuery query) {
        if (query instanceof com.panfan.hivelineage.v2.syntax.SelectQuerySyntax) {
            com.panfan.hivelineage.v2.syntax.SelectBodySyntax body = ((com.panfan.hivelineage.v2.syntax.SelectQuerySyntax) query).getBody();
            if (body.getFrom() instanceof com.panfan.hivelineage.v2.syntax.DerivedFromSyntax
                && ((com.panfan.hivelineage.v2.syntax.DerivedFromSyntax) body.getFrom()).isSyntheticWrapper()) {
                return containsStar(((com.panfan.hivelineage.v2.syntax.DerivedFromSyntax) body.getFrom()).getQuery());
            }
            for (com.panfan.hivelineage.v2.syntax.SelectItemSyntax item
                : ((com.panfan.hivelineage.v2.syntax.SelectQuerySyntax) query).getBody().getSelectItems()) {
                if (item.getExpression() instanceof com.panfan.hivelineage.v2.syntax.StarSyntax) {
                    return true;
                }
            }
        } else if (query instanceof com.panfan.hivelineage.v2.syntax.SetQuerySyntax) {
            for (com.panfan.hivelineage.v2.syntax.SyntaxQuery branch
                : ((com.panfan.hivelineage.v2.syntax.SetQuerySyntax) query).getBody().getBranches()) {
                if (containsStar(branch)) {
                    return true;
                }
            }
        }
        return false;
    }

    private OutputSchema targetSchema(QualifiedName name, ObjectId object, NodeId writeId,
                                      List<IdentifierPart> declared, OutputSchema derived) {
        CatalogObjectResolver resolver = new CatalogObjectResolver(work.context);
        CatalogObjectResolver.Resolution resolved = resolver.canonical(name, object);
        if (!work.requested(BindingRequirement.SINK_MAPPING)) {
            work.declare(object, name, resolved.kind(), SchemaState.NOT_REQUESTED,
                Collections.<FieldIdentity>emptyList(), null);
            return OutputSchema.notRequested();
        }
        List<OutputSlot> slots = new ArrayList<>();
        if (!declared.isEmpty()) {
            int position = 0;
            for (IdentifierPart column : declared) {
                FieldIdentity field = work.field(object, column.getValue());
                slots.add(new OutputSlot(new SlotId(writeId.getValue() + "/target-" + position++), field.getDisplayName(), TypeRef.unknown(), field));
            }
        } else if (derived != null) {
            int position = 0;
            for (OutputSlot input : BindingWork.slots(derived)) {
                FieldIdentity field = work.field(object, input.getDisplayName());
                slots.add(new OutputSlot(new SlotId(writeId.getValue() + "/target-" + position++), field.getDisplayName(), input.getType(), field));
            }
            if (derived.getState() != SchemaState.KNOWN) {
                work.declare(object, name, resolved.kind(), SchemaState.UNKNOWN,
                    fields(slots), null);
                return BindingWork.partialSchema(slots, new NodeId(writeId.getValue() + "/target-width"), "TARGET_SCHEMA_UNKNOWN");
            }
        } else {
            resolved.requireAvailable();
            if (resolved.uncertain) {
                work.warn("SESSION_OBJECT_UNCERTAIN", "Sink schema was invalidated by a prior uncertain effect", null);
                work.declare(object, name, resolved.kind(), SchemaState.UNKNOWN,
                    Collections.<FieldIdentity>emptyList(), null);
                return BindingWork.unknown(new NodeId(writeId.getValue() + "/target-width"), "SESSION_OBJECT_UNCERTAIN");
            }
            LookupResult<RelationSchema> metadata = resolver.schema(resolved);
            work.diagnostics.addAll(metadata.getDiagnostics().getDiagnostics());
            RelationSchema loaded = metadata.getStatus() == LookupStatus.FOUND ? metadata.getValue().get() : null;
            if (loaded == null || loaded.getState() != SchemaState.KNOWN) {
                work.warn("METADATA_NOT_FOUND", "Sink schema is unavailable for " + name.getDisplayName(), null);
                work.declare(object, name, resolved.kind(), SchemaState.UNKNOWN,
                    Collections.<FieldIdentity>emptyList(), null);
                return BindingWork.unknown(new NodeId(writeId.getValue() + "/target-width"), "TARGET_SCHEMA_UNKNOWN");
            }
            for (ColumnMetadata column : loaded.getColumns()) {
                FieldIdentity field = work.field(object, column.getName());
                slots.add(new OutputSlot(new SlotId(writeId.getValue() + "/target-" + column.getPosition()), field.getDisplayName(), column.getType(), field));
            }
        }
        work.declare(object, name, resolved.kind(), SchemaState.KNOWN, fields(slots), null);
        return OutputSchema.known(slots);
    }

    private PartitionScope partitions(InsertTargetSyntax target, ObjectId object, BoundRelation query, ReferenceScope scope) {
        List<PartitionScope.Assignment> assignments = new ArrayList<>();
        int dynamicCount = 0;
        int staticCount = 0;
        for (PartitionEntrySyntax partition : target.getPartitions()) {
            dynamicCount += partition.getStaticValue() == null ? 1 : 0;
            staticCount += partition.getStaticValue() == null ? 0 : 1;
        }
        int dynamicIndex = Math.max(0, BindingWork.slots(query.getOutputSchema()).size() - dynamicCount);
        for (PartitionEntrySyntax partition : target.getPartitions()) {
            FieldIdentity field = work.field(object, partition.getColumn().getValue());
            BoundExpression expression;
            if (partition.getStaticValue() != null) {
                expression = new ExpressionBinder(work).bind(partition.getStaticValue(), scope);
                assignments.add(new PartitionScope.Assignment(field, PartitionScope.AssignmentKind.STATIC, expression));
            } else {
                List<OutputSlot> slots = BindingWork.slots(query.getOutputSchema());
                if (query.getOutputSchema().getState() == SchemaState.KNOWN && dynamicIndex < slots.size()) {
                    OutputSlot slot = slots.get(dynamicIndex++);
                    expression = new Expressions.SlotRef(new NodeId(query.getId().getValue() + "/partition-" + dynamicIndex),
                        slot.getType(), null, slot.getId(), Collections.<String>emptyList());
                } else {
                    expression = new Expressions.UnknownExpression(new NodeId(query.getId().getValue() + "/partition-" + dynamicIndex++),
                        TypeRef.unknown(), null, Collections.<BoundExpression>emptyList(), "DYNAMIC_PARTITION_UNKNOWN");
                }
                assignments.add(new PartitionScope.Assignment(field, PartitionScope.AssignmentKind.DYNAMIC, expression));
            }
            if (work.requested(BindingRequirement.SINK_MAPPING)) {
                work.declareField(object, field);
            }
        }
        PartitionScope.Kind kind = dynamicCount > 0 && staticCount > 0 ? PartitionScope.Kind.MIXED
            : dynamicCount > 0 ? PartitionScope.Kind.DYNAMIC : staticCount > 0 ? PartitionScope.Kind.STATIC : PartitionScope.Kind.UNPARTITIONED;
        return new PartitionScope(kind, assignments);
    }

    private List<FieldIdentity> explicitFields(ObjectId object, List<IdentifierPart> columns) {
        List<FieldIdentity> fields = new ArrayList<>();
        for (IdentifierPart column : columns) {
            FieldIdentity field = work.field(object, column.getValue());
            if (fields.contains(field)) {
                throw new BindingFailure("DUPLICATE_TARGET_COLUMN", "Target column list contains duplicate names");
            }
            fields.add(field);
        }
        return fields;
    }

    private List<FieldIdentity> fields(List<OutputSlot> slots) {
        List<FieldIdentity> fields = new ArrayList<>();
        for (OutputSlot slot : slots) {
            fields.add(slot.getFieldIdentity().get());
        }
        return fields;
    }

    private void accept(SinkPlanningResult result) {
        work.diagnostics.addAll(result.getDiagnostics().getDiagnostics());
        if (result.getWrite().isPresent()) {
            work.writes.add(result.getWrite().get());
            for (com.panfan.hivelineage.v2.ir.statement.WriteAssignment assignment : result.getWrite().get().getAssignments()) {
                work.declareField(result.getWrite().get().getTargetObject(), assignment.getTargetField());
            }
            MappingCoverage coverage = result.getWrite().get().getMappingCoverage();
            if (coverage == MappingCoverage.PARTIAL || coverage == MappingCoverage.UNKNOWN) {
                work.partial = true;
            }
        } else {
            work.failed = true;
        }
    }

    private VersionRef before(ObjectId object) {
        if (work.requested(BindingRequirement.DATASET_VERSIONS) && work.objects.containsKey(object)
            && work.objects.get(object).getKind() == BoundObjectReference.ObjectKind.VIEW) {
            return VersionRef.unknown("VIEW_NOT_MATERIALIZED");
        }
        return work.requested(BindingRequirement.DATASET_VERSIONS)
            ? work.context.getSession().getDatasetVersions().containsKey(object)
                ? work.context.getSession().getDatasetVersions().get(object).getVersion()
                : VersionRef.known(new DatasetVersionId(object.getValue() + "/session/"
                    + work.context.getSession().getInitialContext().getSessionId() + "/script-entry"))
            : VersionRef.notRequested();
    }

    private VersionRef after(ObjectId object, NodeId writeId) {
        if (work.requested(BindingRequirement.DATASET_VERSIONS) && work.objects.containsKey(object)
            && work.objects.get(object).getKind() == BoundObjectReference.ObjectKind.VIEW) {
            return VersionRef.unknown("VIEW_NOT_MATERIALIZED");
        }
        return work.requested(BindingRequirement.DATASET_VERSIONS)
            ? VersionRef.known(new DatasetVersionId(object.getValue() + "/session/"
                + work.context.getSession().getInitialContext().getSessionId() + "/write/" + writeId.getValue()))
            : VersionRef.notRequested();
    }

    private Effects.Precondition always() {
        return new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
    }
}
