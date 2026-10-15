package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.BoundRelationReference;
import com.panfan.hivelineage.v2.ir.statement.BoundQueryBlockReference;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.syntax.SyntaxNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BindingWork {
    final BindingContext context;
    final BindingDemand demand;
    final TypeAnalyzer types;
    final Map<NodeId, BoundRelation> relations = new LinkedHashMap<>();
    final Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<>();
    final Map<ScopeId, BoundScopeReference> scopes = new LinkedHashMap<>();
    final Map<RelationInstanceId, BoundRelationReference> relationReferences = new LinkedHashMap<>();
    final Map<QueryBlockId, BoundQueryBlockReference> queryBlocks = new LinkedHashMap<>();
    final List<QueryResult> queryResults = new ArrayList<>();
    final List<BoundWrite> writes = new ArrayList<>();
    final List<BoundEffect> effects = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();
    final List<DefinitionRequest> definitionRequests = new ArrayList<>();
    final Map<NodeId, BoundRelation> cteRoots = new LinkedHashMap<>();
    final Set<NodeId> activeDefinitions = new LinkedHashSet<>();
    final List<DefinitionId> definitionPath = new ArrayList<>();
    boolean partial;
    boolean failed;
    boolean unsupported;

    BindingWork(BindingContext context, BindingDemand demand, TypeAnalyzer types) {
        this.context = context;
        this.demand = demand;
        this.types = types;
    }

    com.panfan.hivelineage.v2.ir.TypeRef analyzeType(com.panfan.hivelineage.v2.ir.expression.BoundExpression expression) {
        return types.analyze(expression, context.getFunctions(), context.getDialect().getSemanticRuleVersion()).getType();
    }

    boolean requested(BindingRequirement requirement) {
        return demand.getRequirements().contains(requirement);
    }

    NodeId id(SyntaxNode node, ReferenceScope scope, String suffix) {
        return new NodeId(scope.prefix + node.getNodeId().getValue() + "/" + suffix);
    }

    void put(BoundRelation relation) {
        relations.put(relation.getId(), relation);
    }

    void warn(String code, String message, SourceRef source) {
        diagnostics.add(new Diagnostic(AnalysisStage.BINDING, code, message, source));
        partial = true;
    }

    void error(String code, String message, SourceRef source) {
        warn(code, message, source);
        failed = true;
    }

    DiagnosticBag bag() {
        return new DiagnosticBag(diagnostics);
    }

    ObjectId objectId(QualifiedName name) {
        CatalogObjectResolver resolver = new CatalogObjectResolver(context);
        return resolver.canonical(name, resolver.identity(name)).object;
    }

    FieldIdentity field(ObjectId object, String name) {
        return new FieldIdentity(object.getValue() + "/field/" + SemanticNames.normalize(name), SemanticNames.normalize(name));
    }

    void declare(ObjectId id, QualifiedName name, BoundObjectReference.ObjectKind kind, SchemaState state,
                 List<FieldIdentity> fields, DefinitionId definition) {
        objects.put(id, new BoundObjectReference(id, name, kind, state, fields,
                context.getMetadataContext().getSourceId(), definition));
    }

    void declareField(ObjectId object, FieldIdentity field) {
        BoundObjectReference previous = objects.get(object);
        if (previous == null) {
            return;
        }
        List<FieldIdentity> fields = new ArrayList<>(previous.getFields());
        if (!fields.contains(field)) {
            fields.add(field);
        }
        declare(object, previous.getName(), previous.getKind(),
                previous.getSchemaState() == SchemaState.KNOWN ? SchemaState.KNOWN : SchemaState.UNKNOWN,
                fields, previous.getDefinitionId().orElse(null));
    }

    static List<OutputSlot> slots(OutputSchema schema) {
        List<OutputSlot> slots = new ArrayList<>();
        for (OutputSegment segment : schema.getSegments()) {
            if (segment instanceof KnownOutputSegment) {
                slots.addAll(((KnownOutputSegment) segment).getSlots());
            }
        }
        return slots;
    }

    static List<SlotMapping> mappings(OutputSchema schema) {
        List<SlotMapping> mappings = new ArrayList<>();
        for (OutputSlot slot : slots(schema)) {
            mappings.add(new SlotMapping(slot.getId(), slot.getId()));
        }
        return mappings;
    }

    static OutputSchema unknown(NodeId id, String code) {
        return new OutputSchema(SchemaState.UNKNOWN,
                Collections.<OutputSegment>singletonList(new UnknownOutputSegment(id, code)));
    }

    static OutputSchema partialSchema(List<OutputSlot> slots, NodeId id, String code) {
        List<OutputSegment> segments = new ArrayList<>();
        if (!slots.isEmpty()) {
            segments.add(new KnownOutputSegment(slots));
        }
        segments.add(new UnknownOutputSegment(id, code));
        return new OutputSchema(SchemaState.UNKNOWN, segments);
    }

    static OutputSchema combined(OutputSchema left, OutputSchema right, boolean outputs) {
        if (!outputs && left.getState() == SchemaState.NOT_REQUESTED && right.getState() == SchemaState.NOT_REQUESTED) {
            return OutputSchema.notRequested();
        }
        List<OutputSegment> segments = new ArrayList<>(left.getSegments());
        segments.addAll(right.getSegments());
        SchemaState state = left.getState() == SchemaState.KNOWN && right.getState() == SchemaState.KNOWN
                ? SchemaState.KNOWN : SchemaState.UNKNOWN;
        return new OutputSchema(state, segments);
    }
}
