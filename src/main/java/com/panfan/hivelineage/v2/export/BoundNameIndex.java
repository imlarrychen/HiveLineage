package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.DirectFieldEndpoint;
import com.panfan.hivelineage.v2.analysis.ExternalDatasetEndpoint;
import com.panfan.hivelineage.v2.analysis.LineageEndpoint;
import com.panfan.hivelineage.v2.analysis.PureIrIndex;
import com.panfan.hivelineage.v2.analysis.RowSetEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotEndpoint;
import com.panfan.hivelineage.v2.analysis.UnknownDirectEndpoint;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundRelationReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Displays bound identities only. Missing declarations are never recovered by parsing IDs or SQL. */
public final class BoundNameIndex {
    private final BoundAnalysisUnit unit;
    private final PureIrIndex index;
    private final Map<ObjectId, Map<FieldId, String>> objectFields = new LinkedHashMap<ObjectId, Map<FieldId, String>>();
    private final Map<RelationInstanceId, Map<FieldId, String>> occurrenceFields =
            new LinkedHashMap<RelationInstanceId, Map<FieldId, String>>();

    public BoundNameIndex(BoundAnalysisUnit unit) {
        this.unit = unit;
        this.index = new PureIrIndex(unit);
        for (BoundObjectReference object : unit.getObjects().values()) {
            for (FieldIdentity field : object.getFields()) {
                objectField(object.getId(), field);
            }
        }
        for (BoundRelation relation : unit.getRelations()) {
            relationOwner(relation).ifPresent(owner -> {
                for (OutputSlot slot : IrTraversal.knownSlots(relation.getOutputSchema())) {
                    slot.getFieldIdentity().ifPresent(field -> occurrenceField(owner, field,
                            index.object(owner).isPresent() ? field.getDisplayName() : slot.getDisplayName()));
                }
            });
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                field(expression);
            }
        }
        for (BoundWrite write : unit.getWrites()) {
            for (WriteAssignment assignment : write.getAssignments()) {
                objectField(write.getTargetObject(), assignment.getTargetField());
                field(assignment.getExpression());
            }
            for (PartitionScope.Assignment partition : write.getPartitionScope().getAssignments()) {
                objectField(write.getTargetObject(), partition.getField());
                field(partition.getExpression());
            }
            write.getCondition().ifPresent(this::field);
        }
    }

    private void field(BoundExpression expression) {
        if (expression instanceof QualifiedFieldHandle) {
            QualifiedFieldHandle handle = (QualifiedFieldHandle) expression;
            occurrenceField(handle.getOwner(), handle.getFieldIdentity());
        } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
            CorrelatedQualifiedFieldHandle handle = (CorrelatedQualifiedFieldHandle) expression;
            occurrenceField(handle.getOwner(), handle.getFieldIdentity());
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            field(child);
        }
    }

    private void objectField(ObjectId owner, FieldIdentity field) {
        objectFields.computeIfAbsent(owner, key -> new LinkedHashMap<FieldId, String>())
                .putIfAbsent(field.getId(), field.getDisplayName());
    }

    private void occurrenceField(RelationInstanceId owner, FieldIdentity field) {
        occurrenceField(owner, field, field.getDisplayName());
    }

    private void occurrenceField(RelationInstanceId owner, FieldIdentity field, String display) {
        occurrenceFields.computeIfAbsent(owner, key -> new LinkedHashMap<FieldId, String>())
                .putIfAbsent(field.getId(), display);
        index.object(owner).ifPresent(object -> objectField(object, field));
    }

    private Optional<RelationInstanceId> relationOwner(BoundRelation relation) {
        if (relation instanceof Relations.Scan) {
            return Optional.of(((Relations.Scan) relation).getInstanceId());
        }
        if (relation instanceof Relations.DefinitionInstance) {
            return Optional.of(((Relations.DefinitionInstance) relation).getInstanceId());
        }
        return Optional.empty();
    }

    private String field(DirectFieldEndpoint endpoint) {
        String name = occurrenceFields.getOrDefault(endpoint.getOwner(), Collections.emptyMap()).get(endpoint.getFieldId());
        return name != null ? name : endpoint.getObjectId().flatMap(object -> declaredField(object, endpoint.getFieldId())).orElse(null);
    }

    public Optional<String> object(ObjectId object) {
        BoundObjectReference declaration = unit.getObjects().get(object);
        return declaration == null ? Optional.<String>empty() : Optional.of(declaration.getName().getDisplayName());
    }

    public Optional<String> object(LineageEndpoint endpoint) {
        if (endpoint instanceof ExternalDatasetEndpoint) {
            return Optional.of(((ExternalDatasetEndpoint) endpoint).getDatasetIdentity());
        }
        if (endpoint instanceof UnknownDirectEndpoint) {
            List<String> parts = ((UnknownDirectEndpoint) endpoint).getDisplayName().getParts();
            return parts.size() > 1 ? Optional.of(String.join(".", parts.subList(0, parts.size() - 1))) : Optional.empty();
        }
        if (endpoint instanceof ColumnEndpoint) {
            return object(((ColumnEndpoint) endpoint).getObjectId());
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            return directObject(((DirectFieldEndpoint) endpoint).getOwner(), ((DirectFieldEndpoint) endpoint).getObjectId());
        }
        if (endpoint instanceof RowSetEndpoint) {
            return index.object(((RowSetEndpoint) endpoint).getRelationId()).flatMap(this::object);
        }
        if (endpoint instanceof SlotEndpoint) {
            return slotOwner(((SlotEndpoint) endpoint).getSlotId()).flatMap(owner -> directObject(owner, index.object(owner)));
        }
        return Optional.empty();
    }

    public Optional<String> physicalObject(LineageEndpoint endpoint) {
        if (endpoint instanceof UnknownDirectEndpoint) {
            return Optional.empty();
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            return ((DirectFieldEndpoint) endpoint).getObjectId().flatMap(this::object);
        }
        return object(endpoint);
    }

    public Optional<String> column(LineageEndpoint endpoint, boolean direct) {
        if (endpoint instanceof UnknownDirectEndpoint) {
            return direct ? Optional.of(((UnknownDirectEndpoint) endpoint).getDisplayName().getDisplayName()) : Optional.empty();
        }
        if (endpoint instanceof ColumnEndpoint) {
            ColumnEndpoint field = (ColumnEndpoint) endpoint;
            return column(object(field.getObjectId()), declaredField(field.getObjectId(), field.getFieldId()).orElse(null), field.getFieldPath());
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            DirectFieldEndpoint field = (DirectFieldEndpoint) endpoint;
            Optional<String> owner = direct ? directColumnOwner(field.getOwner(), field.getObjectId())
                    : field.getObjectId().flatMap(this::object);
            return column(owner, field(field), field.getFieldPath());
        }
        if (endpoint instanceof SlotEndpoint) {
            SlotEndpoint slot = (SlotEndpoint) endpoint;
            Optional<String> owner = slotOwner(slot.getSlotId()).flatMap(instance -> direct
                    ? directColumnOwner(instance, index.object(instance)) : directObject(instance, index.object(instance)));
            return index.slot(slot.getSlotId()).flatMap(value -> column(owner, value.getDisplayName(), java.util.Collections.emptyList()));
        }
        return Optional.empty();
    }

    public Optional<String> column(ObjectId object, FieldIdentity field) {
        return column(object(object), field.getDisplayName(), java.util.Collections.emptyList());
    }

    private Optional<String> column(Optional<String> owner, String field, List<String> path) {
        if (!owner.isPresent() || field == null) {
            return Optional.empty();
        }
        String suffix = path.isEmpty() ? "" : "." + String.join(".", path);
        return Optional.of(owner.get() + "." + field + suffix);
    }

    private Optional<String> directObject(RelationInstanceId owner, Optional<ObjectId> physicalObject) {
        BoundRelationReference declaration = unit.getRelationReferences().get(owner);
        return physicalObject.isPresent() ? physicalObject.flatMap(this::object)
                : declaration == null ? Optional.<String>empty() : Optional.of(declaration.getAlias().getDisplayName());
    }

    private Optional<String> directColumnOwner(RelationInstanceId owner, Optional<ObjectId> physicalObject) {
        if (!physicalObject.map(this::temporary).orElse(false)) {
            return directObject(owner, physicalObject);
        }
        BoundRelationReference declaration = unit.getRelationReferences().get(owner);
        return declaration == null ? Optional.empty() : Optional.of(declaration.getAlias().getDisplayName());
    }

    private Optional<RelationInstanceId> slotOwner(com.panfan.hivelineage.v2.ir.identity.SlotId slot) {
        Optional<BoundRelation> owner = index.slotOwner(slot);
        if (!owner.isPresent()) {
            return Optional.empty();
        }
        if (owner.get() instanceof Relations.Scan) {
            return Optional.of(((Relations.Scan) owner.get()).getInstanceId());
        }
        if (owner.get() instanceof Relations.DefinitionInstance) {
            return Optional.of(((Relations.DefinitionInstance) owner.get()).getInstanceId());
        }
        return Optional.empty();
    }

    public boolean temporary(ObjectId object) {
        BoundObjectReference declaration = unit.getObjects().get(object);
        return declaration != null && declaration.getKind() == BoundObjectReference.ObjectKind.TEMPORARY;
    }

    public boolean view(ObjectId object) {
        BoundObjectReference declaration = unit.getObjects().get(object);
        return declaration != null && declaration.getKind() == BoundObjectReference.ObjectKind.VIEW;
    }

    public boolean temporary(LineageEndpoint endpoint) {
        return endpointObject(endpoint).map(this::temporary).orElse(false);
    }

    public Optional<com.panfan.hivelineage.v2.common.QualifiedName> declaredObject(ObjectId id) {
        BoundObjectReference declaration = unit.getObjects().get(id);
        return declaration == null ? Optional.empty() : Optional.of(declaration.getName());
    }

    public Optional<String> declaredField(ObjectId owner, FieldId id) {
        return Optional.ofNullable(objectFields.getOrDefault(owner, Collections.emptyMap()).get(id));
    }

    /** Unscoped display is available only when all owning declarations agree. */
    public Optional<String> declaredField(FieldId id) {
        String name = null;
        List<Map<FieldId, String>> owners = new ArrayList<Map<FieldId, String>>(objectFields.values());
        owners.addAll(occurrenceFields.values());
        for (Map<FieldId, String> fields : owners) {
            String candidate = fields.get(id);
            if (candidate != null) {
                if (name != null && !name.equals(candidate)) {
                    return Optional.empty();
                }
                name = candidate;
            }
        }
        return Optional.ofNullable(name);
    }

    public Optional<ObjectId> endpointObject(LineageEndpoint endpoint) {
        if (endpoint instanceof ColumnEndpoint) {
            return Optional.of(((ColumnEndpoint) endpoint).getObjectId());
        }
        if (endpoint instanceof DirectFieldEndpoint) {
            return ((DirectFieldEndpoint) endpoint).getObjectId();
        }
        if (endpoint instanceof RowSetEndpoint) {
            return index.object(((RowSetEndpoint) endpoint).getRelationId());
        }
        return Optional.empty();
    }

    public Optional<QualifiedName> endpointName(LineageEndpoint endpoint) {
        if (endpoint instanceof ExternalDatasetEndpoint) {
            return Optional.of(new QualifiedName(Collections.singletonList(((ExternalDatasetEndpoint) endpoint).getDatasetIdentity())));
        }
        return endpointObject(endpoint).flatMap(this::declaredObject);
    }

    public Optional<QualifiedName> nativeObject(LineageEndpoint endpoint, boolean direct) {
        Optional<RelationInstanceId> owner = Optional.empty();
        if (direct && endpoint instanceof DirectFieldEndpoint) {
            owner = Optional.of(((DirectFieldEndpoint) endpoint).getOwner());
        } else if (direct && endpoint instanceof SlotEndpoint) {
            owner = slotOwner(((SlotEndpoint) endpoint).getSlotId());
        } else if (direct && endpoint instanceof UnknownDirectEndpoint) {
            owner = ((UnknownDirectEndpoint) endpoint).getOwner();
        }
        if (owner.isPresent()) {
            BoundRelationReference relation = unit.getRelationReferences().get(owner.get());
            return relation == null ? Optional.empty() : Optional.of(relation.getAlias());
        }
        return endpointName(endpoint);
    }

    public Optional<ColumnAddress> columnAddress(LineageEndpoint endpoint, boolean direct) {
        Optional<QualifiedName> object = nativeObject(endpoint, direct);
        String field = null;
        List<String> suffix = Collections.emptyList();
        if (endpoint instanceof ColumnEndpoint) {
            ColumnEndpoint column = (ColumnEndpoint) endpoint;
            field = declaredField(column.getObjectId(), column.getFieldId()).orElse(null);
            suffix = column.getFieldPath();
        } else if (endpoint instanceof DirectFieldEndpoint) {
            field = field((DirectFieldEndpoint) endpoint);
            suffix = ((DirectFieldEndpoint) endpoint).getFieldPath();
        } else if (endpoint instanceof SlotEndpoint) {
            field = index.slot(((SlotEndpoint) endpoint).getSlotId()).map(OutputSlot::getDisplayName).orElse(null);
        } else if (direct && endpoint instanceof UnknownDirectEndpoint && object.isPresent()) {
            List<String> display = ((UnknownDirectEndpoint) endpoint).getDisplayName().getParts();
            int prefix = object.get().getParts().size();
            if (display.size() > prefix && display.subList(0, prefix).equals(object.get().getParts())) {
                return Optional.of(new ColumnAddress(object.get(), display.subList(prefix, display.size())));
            }
        }
        if (!object.isPresent() || field == null) {
            return Optional.empty();
        }
        List<String> path = new ArrayList<String>();
        path.add(field);
        path.addAll(suffix);
        return Optional.of(new ColumnAddress(object.get(), path));
    }

    public PureIrIndex ir() {
        return index;
    }
}
