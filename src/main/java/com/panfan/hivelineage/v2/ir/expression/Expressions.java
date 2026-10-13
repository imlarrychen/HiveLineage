package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed immutable expressions; text attachments never participate in binding or lineage. */
public final class Expressions {
    private Expressions() {
    }

    public abstract static class Base implements BoundExpression {
        private final NodeId id;
        private final TypeRef type;
        private final SourceRef sourceRef;

        protected Base(NodeId id, TypeRef type, SourceRef sourceRef) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.sourceRef = sourceRef;
        }

        public NodeId getId() {
            return id;
        }

        public TypeRef getType() {
            return type;
        }

        public Optional<SourceRef> getSourceRef() {
            return Optional.ofNullable(sourceRef);
        }
    }

    public static final class LiteralValue {
        public enum Kind { NULL, STRING, BOOLEAN, INTEGER, DECIMAL, DATE, TIMESTAMP, BINARY }
        private final Kind kind;
        private final String value;

        public LiteralValue(Kind kind, String value) {
            this.kind = Objects.requireNonNull(kind, "kind");
            if (kind != Kind.NULL && value == null) {
                throw new IllegalArgumentException("non-null literal requires a value");
            }
            if (kind == Kind.NULL && value != null) {
                throw new IllegalArgumentException("null literal cannot contain a value");
            }
            this.value = value;
        }

        public Kind getKind() {
            return kind;
        }

        public Optional<String> getValue() {
            return Optional.ofNullable(value);
        }
    }

    public static final class WhenThen {
        private final BoundExpression condition;
        private final BoundExpression value;

        public WhenThen(BoundExpression condition, BoundExpression value) {
            this.condition = Objects.requireNonNull(condition, "condition");
            this.value = Objects.requireNonNull(value, "value");
        }

        public BoundExpression getCondition() {
            return condition;
        }

        public BoundExpression getValue() {
            return value;
        }
    }

    public enum ScalarCardinality { VERIFIED_SINGLE, UNKNOWN }
    public static final class Literal extends Base {
        private final LiteralValue value;

        public Literal(NodeId id, TypeRef type, SourceRef sourceRef, LiteralValue value) {
            super(id, type, sourceRef);
            this.value = Objects.requireNonNull(value, "value");
        }

        public LiteralValue getValue() {
            return value;
        }
    }

    public static final class SlotRef extends Base {
        private final SlotId slotId;
        private final List<String> fieldPath;

        public SlotRef(NodeId id, TypeRef type, SourceRef sourceRef, SlotId slotId, List<String> fieldPath) {
            super(id, type, sourceRef);
            this.slotId = Objects.requireNonNull(slotId, "slotId");
            this.fieldPath = ModelCollections.immutableList(fieldPath);
        }

        public SlotId getSlotId() {
            return slotId;
        }

        public List<String> getFieldPath() {
            return fieldPath;
        }
    }

    public static final class CorrelatedSlotRef extends Base {
        private final ScopeId outerScopeId;
        private final SlotId slotId;
        private final List<String> fieldPath;

        public CorrelatedSlotRef(NodeId id, TypeRef type, SourceRef sourceRef, ScopeId outerScopeId, SlotId slotId, List<String> fieldPath) {
            super(id, type, sourceRef);
            this.outerScopeId = Objects.requireNonNull(outerScopeId, "outerScopeId");
            this.slotId = Objects.requireNonNull(slotId, "slotId");
            this.fieldPath = ModelCollections.immutableList(fieldPath);
        }

        public ScopeId getOuterScopeId() {
            return outerScopeId;
        }

        public SlotId getSlotId() {
            return slotId;
        }

        public List<String> getFieldPath() {
            return fieldPath;
        }
    }

    public static final class Call extends Base {
        private final FunctionSymbol function;
        private final List<BoundExpression> arguments;
        private final boolean distinct;

        public Call(NodeId id, TypeRef type, SourceRef sourceRef, FunctionSymbol function, List<BoundExpression> arguments, boolean distinct) {
            super(id, type, sourceRef);
            this.function = Objects.requireNonNull(function, "function");
            this.arguments = ModelCollections.immutableList(arguments);
            this.distinct = distinct;
        }

        public FunctionSymbol getFunction() {
            return function;
        }

        public List<BoundExpression> getArguments() {
            return arguments;
        }

        public boolean getDistinct() {
            return distinct;
        }
    }

    public static final class Case extends Base {
        private final List<WhenThen> branches;
        private final BoundExpression elseValue;

        public Case(NodeId id, TypeRef type, SourceRef sourceRef, List<WhenThen> branches, BoundExpression elseValue) {
            super(id, type, sourceRef);
            this.branches = ModelCollections.immutableList(branches);
            this.elseValue = elseValue;
        }

        public List<WhenThen> getBranches() {
            return branches;
        }

        public Optional<BoundExpression> getElseValue() {
            return Optional.ofNullable(elseValue);
        }
    }

    public static final class Cast extends Base {
        private final BoundExpression expression;
        private final TypeRef targetType;

        public Cast(NodeId id, TypeRef type, SourceRef sourceRef, BoundExpression expression, TypeRef targetType) {
            super(id, type, sourceRef);
            this.expression = Objects.requireNonNull(expression, "expression");
            this.targetType = Objects.requireNonNull(targetType, "targetType");
        }

        public BoundExpression getExpression() {
            return expression;
        }

        public TypeRef getTargetType() {
            return targetType;
        }
    }

    public static final class FieldAccess extends Base {
        private final BoundExpression expression;
        private final FieldIdentity field;

        public FieldAccess(NodeId id, TypeRef type, SourceRef sourceRef, BoundExpression expression, FieldIdentity field) {
            super(id, type, sourceRef);
            this.expression = Objects.requireNonNull(expression, "expression");
            this.field = Objects.requireNonNull(field, "field");
        }

        public BoundExpression getExpression() {
            return expression;
        }

        public FieldIdentity getField() {
            return field;
        }
    }

    public static final class Subscript extends Base {
        private final BoundExpression expression;
        private final BoundExpression index;

        public Subscript(NodeId id, TypeRef type, SourceRef sourceRef, BoundExpression expression, BoundExpression index) {
            super(id, type, sourceRef);
            this.expression = Objects.requireNonNull(expression, "expression");
            this.index = Objects.requireNonNull(index, "index");
        }

        public BoundExpression getExpression() {
            return expression;
        }

        public BoundExpression getIndex() {
            return index;
        }
    }

    public static final class ScalarSubquery extends Base {
        private final NodeId queryRoot;
        private final SlotId resultSlot;
        private final ScalarCardinality cardinality;

        public ScalarSubquery(NodeId id, TypeRef type, SourceRef sourceRef, NodeId queryRoot, SlotId resultSlot, ScalarCardinality cardinality) {
            super(id, type, sourceRef);
            this.queryRoot = Objects.requireNonNull(queryRoot, "queryRoot");
            this.resultSlot = Objects.requireNonNull(resultSlot, "resultSlot");
            this.cardinality = Objects.requireNonNull(cardinality, "cardinality");
        }

        public NodeId getQueryRoot() {
            return queryRoot;
        }

        public SlotId getResultSlot() {
            return resultSlot;
        }

        public ScalarCardinality getCardinality() {
            return cardinality;
        }
    }

    public static final class ExistsSubquery extends Base {
        private final NodeId queryRoot;
        private final boolean negated;

        public ExistsSubquery(NodeId id, TypeRef type, SourceRef sourceRef, NodeId queryRoot, boolean negated) {
            super(id, type, sourceRef);
            this.queryRoot = Objects.requireNonNull(queryRoot, "queryRoot");
            this.negated = negated;
        }

        public NodeId getQueryRoot() {
            return queryRoot;
        }

        public boolean getNegated() {
            return negated;
        }
    }

    public static final class InSubquery extends Base {
        private final BoundExpression value;
        private final NodeId queryRoot;
        private final SlotId resultSlot;
        private final boolean negated;

        public InSubquery(NodeId id, TypeRef type, SourceRef sourceRef, BoundExpression value, NodeId queryRoot, SlotId resultSlot, boolean negated) {
            super(id, type, sourceRef);
            this.value = Objects.requireNonNull(value, "value");
            this.queryRoot = Objects.requireNonNull(queryRoot, "queryRoot");
            this.resultSlot = Objects.requireNonNull(resultSlot, "resultSlot");
            this.negated = negated;
        }

        public BoundExpression getValue() {
            return value;
        }

        public NodeId getQueryRoot() {
            return queryRoot;
        }

        public SlotId getResultSlot() {
            return resultSlot;
        }

        public boolean getNegated() {
            return negated;
        }
    }

    public static final class UnknownReference extends Base {
        private final List<SlotId> candidateSlots;
        private final List<RelationInstanceId> candidateOwners;
        private final String diagnosticCode;
        private final RelationInstanceId displayOwner;
        private final QualifiedName displayReference;

        public UnknownReference(NodeId id, TypeRef type, SourceRef sourceRef, List<SlotId> candidateSlots, List<RelationInstanceId> candidateOwners, String diagnosticCode) {
            this(id, type, sourceRef, candidateSlots, candidateOwners, diagnosticCode, null, null);
        }

        /** Display attachment is not an unresolved identity and cannot be used for rebinding. */
        public UnknownReference(NodeId id, TypeRef type, SourceRef sourceRef, List<SlotId> candidateSlots, List<RelationInstanceId> candidateOwners, String diagnosticCode, RelationInstanceId displayOwner, QualifiedName displayReference) {
            super(id, type, sourceRef);
            this.candidateSlots = ModelCollections.immutableList(candidateSlots);
            this.candidateOwners = ModelCollections.immutableList(candidateOwners);
            this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
            this.displayOwner = displayOwner;
            this.displayReference = displayReference;
        }

        public List<SlotId> getCandidateSlots() {
            return candidateSlots;
        }

        public List<RelationInstanceId> getCandidateOwners() {
            return candidateOwners;
        }

        public Optional<RelationInstanceId> getDisplayOwner() {
            return Optional.ofNullable(displayOwner);
        }

        public Optional<QualifiedName> getDisplayReference() {
            return Optional.ofNullable(displayReference);
        }

        public String getDiagnosticCode() {
            return diagnosticCode;
        }
    }

    public static final class UnknownExpression extends Base {
        private final List<BoundExpression> knownChildren;
        private final String diagnosticCode;

        public UnknownExpression(NodeId id, TypeRef type, SourceRef sourceRef, List<BoundExpression> knownChildren, String diagnosticCode) {
            super(id, type, sourceRef);
            this.knownChildren = ModelCollections.immutableList(knownChildren);
            this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        }

        public List<BoundExpression> getKnownChildren() {
            return knownChildren;
        }

        public String getDiagnosticCode() {
            return diagnosticCode;
        }
    }

    /** Unrequested expression region with separately bound object-only subquery roots. */
    public static final class NotRequestedExpression extends Base {
        private final BindingRequirement requiredFor;
        private final List<NodeId> knownQueryRoots;

        public NotRequestedExpression(NodeId id, TypeRef type, SourceRef sourceRef, BindingRequirement requiredFor, List<NodeId> knownQueryRoots) {
            super(id, type, sourceRef);
            this.requiredFor = Objects.requireNonNull(requiredFor, "requiredFor");
            this.knownQueryRoots = ModelCollections.immutableList(knownQueryRoots);
        }

        public BindingRequirement getRequiredFor() {
            return requiredFor;
        }

        public List<NodeId> getKnownQueryRoots() {
            return knownQueryRoots;
        }
    }
}
