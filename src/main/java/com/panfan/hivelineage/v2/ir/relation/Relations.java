package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable operator nodes; graph edges use stable identities, never deferred callbacks. */
public final class Relations {
    private Relations() {
    }

    public abstract static class Base implements BoundRelation {
        private final NodeId id;
        private final OutputSchema outputSchema;
        private final QueryBlockId queryBlockId;
        private final int sourceOrder;
        private final SourceRef sourceRef;
        private final List<NodeId> inputIds;

        protected Base(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, List<NodeId> inputIds) {
            this.id = Objects.requireNonNull(id, "id");
            this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
            this.queryBlockId = Objects.requireNonNull(queryBlockId, "queryBlockId");
            if (sourceOrder < 0) {
                throw new IllegalArgumentException("sourceOrder must be nonnegative");
            }
            this.sourceOrder = sourceOrder;
            this.sourceRef = sourceRef;
            this.inputIds = ModelCollections.immutableList(inputIds);
        }

        public NodeId getId() {
            return id;
        }

        public OutputSchema getOutputSchema() {
            return outputSchema;
        }

        public QueryBlockId getQueryBlockId() {
            return queryBlockId;
        }

        public int getSourceOrder() {
            return sourceOrder;
        }

        public Optional<SourceRef> getSourceRef() {
            return Optional.ofNullable(sourceRef);
        }

        public List<NodeId> getInputIds() {
            return inputIds;
        }
    }

    public enum JoinType { INNER, LEFT, RIGHT, FULL, CROSS, LEFT_SEMI, LEFT_ANTI }
    public enum JoinForm { EXPLICIT, COMMA }
    public enum PredicateOrigin { ON, WHERE }
    public enum SetOperator { UNION, INTERSECT, EXCEPT }
    public enum DuplicatePolicy { ALL, DISTINCT }
    public enum Direction { ASCENDING, DESCENDING }
    public enum NullOrder { FIRST, LAST, DIALECT_DEFAULT }
    public enum FrameUnit { ROWS, RANGE, GROUPS }
    public enum FrameBoundKind { UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING }

    /** Predicate ownership is bound before Join analysis, including comma/WHERE conditions. */
    public static final class JoinPredicate {
        private final BoundExpression expression;
        private final PredicateOrigin origin;
        private final List<RelationInstanceId> owners;
        private final int sourceOrder;

        public JoinPredicate(BoundExpression expression, PredicateOrigin origin, List<RelationInstanceId> owners, int sourceOrder) {
            this.expression = Objects.requireNonNull(expression, "expression");
            this.origin = Objects.requireNonNull(origin, "origin");
            this.owners = ModelCollections.immutableList(owners);
            if (sourceOrder < 0) {
                throw new IllegalArgumentException("sourceOrder must be nonnegative");
            }
            this.sourceOrder = sourceOrder;
        }

        public BoundExpression getExpression() {
            return expression;
        }

        public PredicateOrigin getOrigin() {
            return origin;
        }

        public List<RelationInstanceId> getOwners() {
            return owners;
        }

        public int getSourceOrder() {
            return sourceOrder;
        }
    }

    public static final class BranchSlot {
        private final SlotId slot;
        private final String diagnosticCode;

        private BranchSlot(SlotId slot, String diagnosticCode) {
            this.slot = slot;
            this.diagnosticCode = diagnosticCode;
        }

        public static BranchSlot known(SlotId slot) {
            return new BranchSlot(Objects.requireNonNull(slot, "slot"), null);
        }

        public static BranchSlot unknown(String diagnosticCode) {
            return new BranchSlot(null, Objects.requireNonNull(diagnosticCode, "diagnosticCode"));
        }

        public Optional<SlotId> getSlot() {
            return Optional.ofNullable(slot);
        }

        public Optional<String> getDiagnosticCode() {
            return Optional.ofNullable(diagnosticCode);
        }
    }

    public static final class SetOutputMapping {
        private final SlotId output;
        private final List<BranchSlot> branchSlots;

        public SetOutputMapping(SlotId output, List<BranchSlot> branchSlots) {
            this.output = Objects.requireNonNull(output, "output");
            this.branchSlots = ModelCollections.immutableList(branchSlots);
        }

        public SlotId getOutput() {
            return output;
        }

        public List<BranchSlot> getBranchSlots() {
            return branchSlots;
        }
    }

    public static final class OrderKey {
        private final BoundExpression expression;
        private final Direction direction;
        private final NullOrder nullOrder;

        public OrderKey(BoundExpression expression, Direction direction, NullOrder nullOrder) {
            this.expression = Objects.requireNonNull(expression, "expression");
            this.direction = Objects.requireNonNull(direction, "direction");
            this.nullOrder = Objects.requireNonNull(nullOrder, "nullOrder");
        }

        public BoundExpression getExpression() {
            return expression;
        }

        public Direction getDirection() {
            return direction;
        }

        public NullOrder getNullOrder() {
            return nullOrder;
        }
    }

    public static final class FrameBound {
        private final FrameBoundKind kind;
        private final BoundExpression offset;

        public FrameBound(FrameBoundKind kind, BoundExpression offset) {
            this.kind = Objects.requireNonNull(kind, "kind");
            boolean bounded = kind == FrameBoundKind.PRECEDING || kind == FrameBoundKind.FOLLOWING;
            if (bounded != (offset != null)) {
                throw new IllegalArgumentException("only bounded frame edges require an offset");
            }
            this.offset = offset;
        }

        public FrameBoundKind getKind() {
            return kind;
        }

        public Optional<BoundExpression> getOffset() {
            return Optional.ofNullable(offset);
        }
    }

    public static final class WindowFrame {
        private final FrameUnit unit;
        private final FrameBound start;
        private final FrameBound end;

        public WindowFrame(FrameUnit unit, FrameBound start, FrameBound end) {
            this.unit = Objects.requireNonNull(unit, "unit");
            this.start = Objects.requireNonNull(start, "start");
            this.end = Objects.requireNonNull(end, "end");
        }

        public FrameUnit getUnit() {
            return unit;
        }

        public FrameBound getStart() {
            return start;
        }

        public FrameBound getEnd() {
            return end;
        }
    }

    public static final class WindowComputation {
        private final OutputAssignment assignment;
        private final List<BoundExpression> partitions;
        private final List<OrderKey> orderKeys;
        private final WindowFrame frame;

        public WindowComputation(OutputAssignment assignment, List<BoundExpression> partitions, List<OrderKey> orderKeys, WindowFrame frame) {
            this.assignment = Objects.requireNonNull(assignment, "assignment");
            this.partitions = ModelCollections.immutableList(partitions);
            this.orderKeys = ModelCollections.immutableList(orderKeys);
            this.frame = frame;
        }

        public OutputAssignment getAssignment() {
            return assignment;
        }

        public List<BoundExpression> getPartitions() {
            return partitions;
        }

        public List<OrderKey> getOrderKeys() {
            return orderKeys;
        }

        public Optional<WindowFrame> getFrame() {
            return Optional.ofNullable(frame);
        }
    }
    public static final class Scan extends Base {
        private final ObjectId objectId;
        private final VersionRef version;
        private final RelationInstanceId instanceId;

        public Scan(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, ObjectId objectId, VersionRef version, RelationInstanceId instanceId) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.<NodeId>emptyList());
            this.objectId = Objects.requireNonNull(objectId, "objectId");
            this.version = Objects.requireNonNull(version, "version");
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        }

        public ObjectId getObjectId() {
            return objectId;
        }

        public VersionRef getVersion() {
            return version;
        }

        public RelationInstanceId getInstanceId() {
            return instanceId;
        }
    }

    public static final class Project extends Base {
        private final NodeId input;
        private final List<OutputAssignment> assignments;

        public Project(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, List<OutputAssignment> assignments) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.assignments = ModelCollections.immutableList(assignments);
        }

        public NodeId getInput() {
            return input;
        }

        public List<OutputAssignment> getAssignments() {
            return assignments;
        }
    }

    public static final class Filter extends Base {
        private final NodeId input;
        private final BoundExpression predicate;
        private final List<SlotMapping> mappings;

        public Filter(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, BoundExpression predicate, List<SlotMapping> mappings) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.predicate = Objects.requireNonNull(predicate, "predicate");
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public NodeId getInput() {
            return input;
        }

        public BoundExpression getPredicate() {
            return predicate;
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }
    }

    public static final class Join extends Base {
        private final NodeId left;
        private final NodeId right;
        private final JoinType joinType;
        private final JoinForm joinForm;
        private final BoundExpression condition;
        private final List<SlotMapping> mappings;
        private final List<JoinPredicate> predicates;

        public Join(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId left, NodeId right, JoinType joinType, JoinForm joinForm, BoundExpression condition, List<SlotMapping> mappings, List<JoinPredicate> predicates) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Arrays.asList(left, right));
            this.left = Objects.requireNonNull(left, "left");
            this.right = Objects.requireNonNull(right, "right");
            this.joinType = Objects.requireNonNull(joinType, "joinType");
            this.joinForm = Objects.requireNonNull(joinForm, "joinForm");
            this.condition = condition;
            this.mappings = ModelCollections.immutableList(mappings);
            this.predicates = ModelCollections.immutableList(predicates);
        }

        public NodeId getLeft() {
            return left;
        }

        public NodeId getRight() {
            return right;
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public JoinForm getJoinForm() {
            return joinForm;
        }

        public Optional<BoundExpression> getCondition() {
            return Optional.ofNullable(condition);
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }

        public List<JoinPredicate> getPredicates() {
            return predicates;
        }
    }

    public static final class Aggregate extends Base {
        private final NodeId input;
        private final List<BoundExpression> groupings;
        private final List<OutputAssignment> assignments;

        public Aggregate(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, List<BoundExpression> groupings, List<OutputAssignment> assignments) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.groupings = ModelCollections.immutableList(groupings);
            this.assignments = ModelCollections.immutableList(assignments);
        }

        public NodeId getInput() {
            return input;
        }

        public List<BoundExpression> getGroupings() {
            return groupings;
        }

        public List<OutputAssignment> getAssignments() {
            return assignments;
        }
    }

    public static final class SetOp extends Base {
        private final List<NodeId> branches;
        private final SetOperator operator;
        private final DuplicatePolicy duplicatePolicy;
        private final List<SetOutputMapping> mappings;

        public SetOp(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, List<NodeId> branches, SetOperator operator, DuplicatePolicy duplicatePolicy, List<SetOutputMapping> mappings) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, branches);
            this.branches = ModelCollections.immutableList(branches);
            this.operator = Objects.requireNonNull(operator, "operator");
            this.duplicatePolicy = Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public List<NodeId> getBranches() {
            return branches;
        }

        public SetOperator getOperator() {
            return operator;
        }

        public DuplicatePolicy getDuplicatePolicy() {
            return duplicatePolicy;
        }

        public List<SetOutputMapping> getMappings() {
            return mappings;
        }
    }

    public static final class Window extends Base {
        private final NodeId input;
        private final List<SlotMapping> retainedSlots;
        private final List<WindowComputation> computations;

        public Window(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, List<SlotMapping> retainedSlots, List<WindowComputation> computations) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.retainedSlots = ModelCollections.immutableList(retainedSlots);
            this.computations = ModelCollections.immutableList(computations);
        }

        public NodeId getInput() {
            return input;
        }

        public List<SlotMapping> getRetainedSlots() {
            return retainedSlots;
        }

        public List<WindowComputation> getComputations() {
            return computations;
        }
    }

    public static final class Generate extends Base {
        private final NodeId input;
        private final FunctionSymbol function;
        private final List<BoundExpression> arguments;
        private final List<SlotId> generatedSlots;
        private final List<SlotMapping> retainedSlots;
        private final boolean outer;

        public Generate(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, FunctionSymbol function, List<BoundExpression> arguments, List<SlotId> generatedSlots, List<SlotMapping> retainedSlots, boolean outer) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.function = Objects.requireNonNull(function, "function");
            this.arguments = ModelCollections.immutableList(arguments);
            this.generatedSlots = ModelCollections.immutableList(generatedSlots);
            this.retainedSlots = ModelCollections.immutableList(retainedSlots);
            this.outer = outer;
        }

        public NodeId getInput() {
            return input;
        }

        public FunctionSymbol getFunction() {
            return function;
        }

        public List<BoundExpression> getArguments() {
            return arguments;
        }

        public List<SlotId> getGeneratedSlots() {
            return generatedSlots;
        }

        public List<SlotMapping> getRetainedSlots() {
            return retainedSlots;
        }

        public boolean getOuter() {
            return outer;
        }
    }

    public static final class Sort extends Base {
        private final NodeId input;
        private final List<OrderKey> orderKeys;
        private final List<SlotMapping> mappings;

        public Sort(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, List<OrderKey> orderKeys, List<SlotMapping> mappings) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.orderKeys = ModelCollections.immutableList(orderKeys);
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public NodeId getInput() {
            return input;
        }

        public List<OrderKey> getOrderKeys() {
            return orderKeys;
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }
    }

    public static final class Limit extends Base {
        private final NodeId input;
        private final BoundExpression count;
        private final BoundExpression offset;
        private final List<SlotMapping> mappings;

        public Limit(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, BoundExpression count, BoundExpression offset, List<SlotMapping> mappings) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.count = Objects.requireNonNull(count, "count");
            this.offset = offset;
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public NodeId getInput() {
            return input;
        }

        public BoundExpression getCount() {
            return count;
        }

        public Optional<BoundExpression> getOffset() {
            return Optional.ofNullable(offset);
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }
    }

    public static final class Distinct extends Base {
        private final NodeId input;
        private final List<SlotId> keys;
        private final List<SlotMapping> mappings;

        public Distinct(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, List<SlotId> keys, List<SlotMapping> mappings) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.keys = ModelCollections.immutableList(keys);
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public NodeId getInput() {
            return input;
        }

        public List<SlotId> getKeys() {
            return keys;
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }
    }

    public static final class DefinitionInstance extends Base {
        private final NodeId input;
        private final DefinitionId definitionId;
        private final ObjectId physicalObject;
        private final RelationInstanceId instanceId;
        private final VersionRef version;
        private final List<SlotMapping> mappings;

        public DefinitionInstance(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, DefinitionId definitionId, RelationInstanceId instanceId, VersionRef version, List<SlotMapping> mappings) {
            this(id, outputSchema, queryBlockId, sourceOrder, sourceRef, input, definitionId, instanceId, version, mappings, null);
        }

        public DefinitionInstance(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, NodeId input, DefinitionId definitionId, RelationInstanceId instanceId, VersionRef version, List<SlotMapping> mappings, ObjectId physicalObject) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.singletonList(input));
            this.input = Objects.requireNonNull(input, "input");
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
            this.physicalObject = physicalObject;
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.version = Objects.requireNonNull(version, "version");
            this.mappings = ModelCollections.immutableList(mappings);
        }

        public NodeId getInput() {
            return input;
        }

        public DefinitionId getDefinitionId() {
            return definitionId;
        }

        public Optional<ObjectId> getPhysicalObject() {
            return Optional.ofNullable(physicalObject);
        }

        public RelationInstanceId getInstanceId() {
            return instanceId;
        }

        public VersionRef getVersion() {
            return version;
        }

        public List<SlotMapping> getMappings() {
            return mappings;
        }
    }

    public static final class UnknownRelation extends Base {
        private final List<NodeId> knownInputs;
        private final String diagnosticCode;

        public UnknownRelation(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, List<NodeId> knownInputs, String diagnosticCode) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, knownInputs);
            this.knownInputs = ModelCollections.immutableList(knownInputs);
            this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        }

        public List<NodeId> getKnownInputs() {
            return knownInputs;
        }

        public String getDiagnosticCode() {
            return diagnosticCode;
        }
    }

    /** Finite literal/expression row source, including a one-row zero-column SELECT input. */
    public static final class Values extends Base {
        private final List<List<BoundExpression>> rows;

        public Values(NodeId id, OutputSchema outputSchema, QueryBlockId queryBlockId, int sourceOrder, SourceRef sourceRef, List<List<BoundExpression>> rows) {
            super(id, outputSchema, queryBlockId, sourceOrder, sourceRef, Collections.emptyList());
            List<List<BoundExpression>> copy = new ArrayList<>();
            for (List<BoundExpression> row : rows) {
                copy.add(ModelCollections.immutableList(row));
            }
            this.rows = ModelCollections.immutableList(copy);
        }

        public List<List<BoundExpression>> getRows() {
            return rows;
        }
    }
}
