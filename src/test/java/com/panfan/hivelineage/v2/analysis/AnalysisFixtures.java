package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.NotRequestedRegion;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.semantic.BindingDemand;
import com.panfan.hivelineage.v2.semantic.DefaultIrVerifier;
import com.panfan.hivelineage.v2.semantic.VerificationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL-free graphs with explicit object declarations and real verification.
 */
public final class AnalysisFixtures {
    private AnalysisFixtures() {
    }

    public static OutputSlot output(String id) {
        return new OutputSlot(new SlotId(id), id, TypeRef.unknown(), new FieldIdentity(id, id));
    }

    public static Relations.Scan scan(String id, String object, String block, String... fields) {
        List<OutputSlot> slots = new ArrayList<OutputSlot>();
        for (String field : fields) {
            slots.add(new OutputSlot(new SlotId(id + "." + field), field, TypeRef.unknown(), new FieldIdentity(object + "." + field, field)));
        }
        return new Relations.Scan(new NodeId(id), OutputSchema.known(slots), new QueryBlockId(block), 0, null,
                new ObjectId(object), VersionRef.notRequested(), new RelationInstanceId(id + ".instance"));
    }

    public static Expressions.SlotRef ref(String id, String slot) {
        return new Expressions.SlotRef(new NodeId(id), TypeRef.unknown(), null, new SlotId(slot), Collections.emptyList());
    }

    public static Expressions.Literal literal(String id, String value) {
        return new Expressions.Literal(new NodeId(id), TypeRef.unknown(), null,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.STRING, value));
    }

    public static Expressions.Call call(String id, String name, FunctionSymbol.FunctionKind kind, BoundExpression... arguments) {
        return new Expressions.Call(new NodeId(id), TypeRef.unknown(), null,
                new ResolvedFunctionSymbol(new QualifiedName(Collections.singletonList(name)), kind,
                        (kind == FunctionSymbol.FunctionKind.OPERATOR ? "operator." : "function.") + name.toUpperCase(java.util.Locale.ROOT)),
                Arrays.asList(arguments), false);
    }

    public static List<SlotMapping> passthrough(OutputSchema schema) {
        List<SlotMapping> mappings = new ArrayList<SlotMapping>();
        for (OutputSlot slot : IrTraversal.knownSlots(schema)) {
            mappings.add(new SlotMapping(slot.getId(), slot.getId()));
        }
        return mappings;
    }

    public static BoundAnalysisUnit unit(List<BoundRelation> relations, List<BoundWrite> writes) {
        return unit(relations, writes, Collections.emptyMap());
    }

    public static BoundAnalysisUnit unit(List<BoundRelation> relations, List<BoundWrite> writes, Map<ScopeId, BoundScopeReference> scopes) {
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<ObjectId, BoundObjectReference>();
        for (BoundRelation relation : relations) {
            if (relation instanceof Relations.Scan) {
                Relations.Scan scan = (Relations.Scan) relation;
                List<FieldIdentity> fields = new ArrayList<FieldIdentity>();
                for (OutputSlot slot : IrTraversal.knownSlots(scan.getOutputSchema())) {
                    slot.getFieldIdentity().ifPresent(fields::add);
                }
                objects.put(scan.getObjectId(), new BoundObjectReference(scan.getObjectId(), new QualifiedName(Arrays.asList("db", scan.getObjectId().getValue())),
                        BoundObjectReference.ObjectKind.TABLE, scan.getOutputSchema().getState(), fields));
            }
        }
        for (BoundWrite write : writes) {
            List<FieldIdentity> fields = new ArrayList<FieldIdentity>();
            write.getAssignments().forEach(assignment -> fields.add(assignment.getTargetField()));
            objects.put(write.getTargetObject(), new BoundObjectReference(write.getTargetObject(), new QualifiedName(Arrays.asList("db", "target")),
                    BoundObjectReference.ObjectKind.TABLE, SchemaState.KNOWN, fields));
        }
        Map<BindingRequirement, BindingCoverage.State> coverage = new LinkedHashMap<BindingRequirement, BindingCoverage.State>();
        for (BindingRequirement requirement : BindingRequirement.values()) {
            coverage.put(requirement, BindingCoverage.State.COMPLETE);
        }
        return new BoundAnalysisUnit(new NodeId("statement"), relations, Collections.<QueryResult>emptyList(), writes,
                Collections.<BoundEffect>emptyList(), new BindingCoverage(Collections.singletonMap(new NodeId("statement"), coverage)),
                DiagnosticBag.empty(), Collections.<NotRequestedRegion>emptyList(), BoundAnalysisUnit.ExecutionAssumption.PRECEDING_STATEMENTS_SUCCEED,
                objects, scopes);
    }

    public static VerifiedAnalysisUnit verify(BoundAnalysisUnit unit, BindingRequirement... requested) {
        Set<BindingRequirement> requirements = EnumSet.noneOf(BindingRequirement.class);
        requirements.addAll(Arrays.asList(requested));
        VerificationResult result = new DefaultIrVerifier().verify(unit, new BindingDemand(requirements, Collections.emptySet()));
        if (!result.getVerifiedUnit().isPresent()) {
            StringBuilder errors = new StringBuilder();
            result.getDiagnostics().getDiagnostics().forEach(item -> errors.append(item.getCode()).append(':').append(item.getMessage()).append('\n'));
            throw new IllegalStateException(errors.toString());
        }
        return result.getVerifiedUnit().get();
    }
}
