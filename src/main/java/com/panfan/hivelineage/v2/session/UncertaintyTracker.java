package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owns uncertainty propagation and distinguishes inherited evidence from this commit's outcome. */
final class UncertaintyTracker {
    private final Set<ObjectId> objects;
    private final Set<VariableKey> variables;
    private final List<Diagnostic> diagnostics;
    private boolean catalogUncertain;
    private boolean databaseUncertain;
    private boolean changedUncertainly;

    UncertaintyTracker(UncertaintyState before, DiagnosticBag proposed) {
        objects = new LinkedHashSet<>(before.getObjects());
        variables = new LinkedHashSet<>(before.getVariables());
        diagnostics = new ArrayList<>(before.getDiagnostics().getDiagnostics());
        diagnostics.addAll(proposed.getDiagnostics());
        catalogUncertain = before.isCatalogUncertain();
        databaseUncertain = before.isDatabaseUncertain();
    }

    void mark(BoundEffect effect, CatalogTransaction catalog, SessionBindings bindings) {
        changedUncertainly = true;
        if (effect instanceof Effects.UnknownEffect) {
            Effects.UnknownEffect unknown = (Effects.UnknownEffect) effect;
            objects.addAll(unknown.getAffectedObjects());
            unknown.getAffectedKeys().forEach(key -> variables.add(bindings.taint(key)));
            catalogUncertain |= unknown.getCatalogMayChange();
        } else if (effect instanceof Effects.SetDatabase) {
            databaseUncertain = true;
        } else if (effect instanceof Effects.SetVariable) {
            variables.add(bindings.taint(((Effects.SetVariable) effect).getKey()));
        } else if (effect instanceof Effects.SetOption) {
            variables.add(bindings.taint(((Effects.SetOption) effect).getKey()));
        } else {
            markObject(effect, catalog);
        }
        catalog.retainUnknownObjects(objects);
        diagnostics.add(new Diagnostic(AnalysisStage.BINDING, "SESSION_EFFECT_UNCERTAIN",
                "Effect cannot be committed with certainty: " + effect.getKind(), SourceRef.synthetic("session")));
    }

    private void markObject(BoundEffect effect, CatalogTransaction catalog) {
        effect.getPrecondition().getObject().ifPresent(objects::add);
        if (effect instanceof Effects.CreateObject) {
            Effects.ObjectShape shape = ((Effects.CreateObject) effect).getShape();
            catalog.declare(shape.getObjectId(), shape.getName());
            objects.add(shape.getObjectId());
        } else if (effect instanceof Effects.AlterObject) {
            objects.add(((Effects.AlterObject) effect).getReplacement().getObjectId());
        } else if (effect instanceof Effects.DropObject) {
            objects.add(((Effects.DropObject) effect).getObjectId());
        } else if (effect instanceof Effects.RenameObject) {
            objects.add(((Effects.RenameObject) effect).getObjectId());
        } else if (effect instanceof Effects.RegisterDefinition) {
            ((Effects.RegisterDefinition) effect).getPhysicalObject().ifPresent(objects::add);
        }
    }

    boolean contains(ObjectId object) {
        return objects.contains(object);
    }

    Set<ObjectId> objects() {
        return Collections.unmodifiableSet(objects);
    }

    void resolve(ObjectId object) {
        objects.remove(object);
    }

    void resolve(VariableKey variable) {
        variables.remove(variable);
    }

    void resolveDatabase() {
        databaseUncertain = false;
    }

    void renamed(ObjectId object) {
        changedUncertainly |= contains(object);
    }

    CommitOutcome outcome() {
        return changedUncertainly ? CommitOutcome.UNCERTAIN : CommitOutcome.COMMITTED;
    }

    UncertaintyState snapshot() {
        return new UncertaintyState(objects, variables, catalogUncertain, databaseUncertain,
                new DiagnosticBag(DiagnosticBag.distinct(diagnostics)));
    }
}
