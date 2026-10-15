package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;

/** Coordinates one unpublished transaction; each participant owns its state invariants. */
final class SessionStateDelta {
    private final SessionSnapshot before;
    private final SessionEffectPlan plan;
    private final SessionBindings bindings;
    private final CatalogTransaction catalog;
    private final UncertaintyTracker uncertainty;
    private final VersionTransitionPolicy versions;

    SessionStateDelta(SessionSnapshot before, SessionEffectPlan plan) {
        this.before = before;
        this.plan = plan;
        bindings = new SessionBindings(before);
        catalog = new CatalogTransaction(before, plan);
        uncertainty = new UncertaintyTracker(before.getUncertainty(), plan.getDiagnostics());
        versions = new VersionTransitionPolicy(before.getDatasetVersions());
    }

    boolean preconditionKnown(BoundEffect effect) {
        return catalog.preconditionKnown(effect, uncertainty);
    }

    boolean preconditionSatisfied(Effects.Precondition precondition) {
        return catalog.preconditionSatisfied(precondition);
    }

    boolean creationBranchApplied(BoundEffect effect) {
        return catalog.creationBranchApplied(effect);
    }

    void suppressCreateVersion(BoundEffect effect) {
        if (effect instanceof Effects.CreateObject) {
            versions.suppress(((Effects.CreateObject) effect).getShape().getObjectId());
        }
    }

    void setDatabase(Effects.SetDatabase effect) {
        bindings.setDatabase(effect.getDatabase());
        uncertainty.resolveDatabase();
    }

    void setVariable(Effects.SetVariable effect) {
        uncertainty.resolve(bindings.setVariable(effect.getKey(), effect.getValue()));
    }

    void setOption(Effects.SetOption effect) {
        uncertainty.resolve(bindings.setVariable(effect.getKey(), effect.getValue()));
    }

    void create(Effects.CreateObject effect) {
        catalog.create(SessionCatalogConversions.entry(effect.getShape(), before));
        uncertainty.resolve(effect.getShape().getObjectId());
    }

    void alter(Effects.AlterObject effect) {
        catalog.put(SessionCatalogConversions.entry(effect.getReplacement(), before));
        uncertainty.resolve(effect.getReplacement().getObjectId());
    }

    void drop(Effects.DropObject effect) {
        versions.drop(effect.getObjectId());
        if (!catalog.drop(effect)) {
            markUncertain(effect);
            return;
        }
        uncertainty.resolve(effect.getObjectId());
    }

    void rename(Effects.RenameObject effect) {
        if (!catalog.rename(effect)) {
            markUncertain(effect);
            return;
        }
        versions.rename(effect.getObjectId(), uncertainty.contains(effect.getObjectId()));
        uncertainty.renamed(effect.getObjectId());
    }

    void register(Effects.RegisterDefinition effect) {
        if (effect.getPhysicalObject().isPresent()) {
            ObjectId object = effect.getPhysicalObject().get();
            if (versions.isSuppressed(object)) {
                return;
            }
            if (uncertainty.contains(object)) {
                markUncertain(effect);
                return;
            }
        }
        catalog.register(SessionCatalogConversions.definition(effect, plan, before), effect);
    }

    void markUncertain(BoundEffect effect) {
        uncertainty.mark(effect, catalog, bindings);
    }

    SessionCommitResult commit() {
        versions.apply(plan, uncertainty.objects());
        UncertaintyState uncertainState = uncertainty.snapshot();
        SessionSnapshot after = new SessionSnapshot(before.getInitialContext(), bindings.database(), bindings.variables(),
                catalog.temporary(), catalog.overlay(), catalog.definitions(), versions.snapshot(), uncertainState,
                before.getRevision() + 1L);
        return new SessionCommitResult(uncertainty.outcome(), after, uncertainState.getDiagnostics());
    }
}
