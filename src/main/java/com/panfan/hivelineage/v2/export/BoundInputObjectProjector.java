package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Displays the declared direct input boundary; physical lineage remains in separate facts. */
public final class BoundInputObjectProjector {
    private final BoundAnalysisUnit unit;
    private final BoundNameIndex names;
    private final boolean includeTemporary;

    public BoundInputObjectProjector(BoundAnalysisUnit unit, BoundNameIndex names, boolean includeTemporary) {
        this.unit = unit;
        this.names = names;
        this.includeTemporary = includeTemporary;
    }

    public List<QualifiedName> inputs(Optional<BoundWrite> write) {
        Set<NodeId> seen = new LinkedHashSet<NodeId>();
        Map<String, QualifiedName> objects = new LinkedHashMap<String, QualifiedName>();
        if (write.isPresent()) {
            write.get().getSourceRelation().ifPresent(root -> relation(root, seen, objects));
        } else if (!unit.getWrites().isEmpty()) {
            for (BoundWrite sink : unit.getWrites()) {
                sink.getSourceRelation().ifPresent(root -> relation(root, seen, objects));
            }
        } else {
            for (QueryResult query : unit.getQueryResults()) {
                if (query.getRole() == QueryResult.Role.MAIN) {
                    relation(query.getRelationRoot(), seen, objects);
                }
            }
        }
        return new ArrayList<QualifiedName>(objects.values());
    }

    public List<QualifiedName> inputsForRoot(NodeId root) {
        Map<String, QualifiedName> objects = new LinkedHashMap<String, QualifiedName>();
        relation(root, new LinkedHashSet<NodeId>(), objects);
        return new ArrayList<QualifiedName>(objects.values());
    }

    private void relation(NodeId id, Set<NodeId> seen, Map<String, QualifiedName> objects) {
        if (!seen.add(id)) {
            return;
        }
        BoundRelation relation = names.ir().getRelations().get(id);
        if (relation instanceof ExternalDatasetRelation) {
            String dataset = ((ExternalDatasetRelation) relation).getDatasetIdentity();
            objects.putIfAbsent("external:" + dataset, new QualifiedName(Collections.singletonList(dataset)));
            return;
        }
        if (relation instanceof Relations.Scan) {
            object(((Relations.Scan) relation).getObjectId(), objects);
            return;
        }
        if (relation instanceof Relations.DefinitionInstance) {
            Optional<ObjectId> object = ((Relations.DefinitionInstance) relation).getPhysicalObject();
            if (object.isPresent() && (includeTemporary || !names.temporary(object.get()))) {
                object(object.get(), objects);
                return;
            }
        }
        if (relation == null) {
            return;
        }
        for (NodeId input : relation.getInputIds()) {
            relation(input, seen, objects);
        }
        for (BoundExpression expression : IrTraversal.expressions(relation)) {
            expression(expression, seen, objects);
        }
    }

    private void object(ObjectId object, Map<String, QualifiedName> objects) {
        names.declaredObject(object).ifPresent(name -> objects.putIfAbsent("object:" + object.getValue(), name));
    }

    private void expression(BoundExpression expression, Set<NodeId> seen, Map<String, QualifiedName> objects) {
        if (expression instanceof Expressions.ScalarSubquery) {
            relation(((Expressions.ScalarSubquery) expression).getQueryRoot(), seen, objects);
        } else if (expression instanceof Expressions.ExistsSubquery) {
            relation(((Expressions.ExistsSubquery) expression).getQueryRoot(), seen, objects);
        } else if (expression instanceof Expressions.InSubquery) {
            relation(((Expressions.InSubquery) expression).getQueryRoot(), seen, objects);
        } else if (expression instanceof Expressions.NotRequestedExpression) {
            for (NodeId root : ((Expressions.NotRequestedExpression) expression).getKnownQueryRoots()) {
                relation(root, seen, objects);
            }
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            expression(child, seen, objects);
        }
    }
}
