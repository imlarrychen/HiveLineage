package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Isolated transaction delta. Only commit publishes a new immutable snapshot. */
final class SessionStateDelta {
    private Optional<QualifiedName> database;
    private final Map<VariableKey, VariableState> variables;
    private final Map<QualifiedName, CatalogEntry> temporary;
    private final Map<QualifiedName, OverlayEntry> overlay;
    private final Map<QualifiedName, LogicalDefinition> definitions;
    private final Map<ObjectId, DataVersionState> versions;
    private final Set<ObjectId> uncertainObjects;
    private final Set<VariableKey> uncertainVariables;
    private final Set<ObjectId> suppressedVersionWrites = new LinkedHashSet<>();
    private final Set<ObjectId> appliedCreateBranches = new LinkedHashSet<>();
    private final List<Diagnostic> diagnostics;
    private boolean catalogUncertain;
    private boolean uncertain;
    private boolean databaseUncertain;
    private final Map<ObjectId, QualifiedName> declaredNames = new LinkedHashMap<>();

    SessionStateDelta(SessionSnapshot before, SessionEffectPlan plan) {
        this.before = before;
        this.plan = plan;
        database = before.getCurrentDatabase();
        variables = new LinkedHashMap<>(before.getVariables());
        temporary = new LinkedHashMap<>(before.getTemporaryCatalog());
        overlay = new LinkedHashMap<>(before.getPersistentOverlay());
        definitions = new LinkedHashMap<>(before.getScriptDefinitions());
        versions = new LinkedHashMap<>(before.getDatasetVersions());
        uncertainObjects = new LinkedHashSet<>(before.getUncertainty().getObjects());
        uncertainVariables = new LinkedHashSet<>(before.getUncertainty().getVariables());
        diagnostics = new ArrayList<>(before.getUncertainty().getDiagnostics().getDiagnostics());
        diagnostics.addAll(plan.getDiagnostics().getDiagnostics());
        catalogUncertain = before.getUncertainty().isCatalogUncertain();
        databaseUncertain = before.getUncertainty().isDatabaseUncertain();
        temporary.values().forEach(entry -> declaredNames.put(entry.getObject().getObjectId(), entry.getObject().getName()));
        overlay.values().forEach(entry -> entry.getEntry().ifPresent(value -> declaredNames.put(value.getObject().getObjectId(), value.getObject().getName())));
        plan.getBoundUnit().ifPresent(unit -> unit.getObjects().values().forEach(object -> declaredNames.put(object.getId(), object.getName())));
    }

    private Optional<QualifiedName> declaredName(ObjectId id) {
        return Optional.ofNullable(declaredNames.get(id));
    }

    boolean preconditionKnown(BoundEffect effect) {
        if (!effect.getPrecondition().getObject().isPresent()) {
            return effect.getPrecondition().getKind() == Effects.Precondition.Kind.ALWAYS;
        }
        ObjectId id = effect.getPrecondition().getObject().get();
        if (temporary.values().stream().anyMatch(entry -> entry.getObject().getObjectId().equals(id))
            || overlay.values().stream().anyMatch(entry -> entry.getEntry().isPresent()
                && entry.getEntry().get().getObject().getObjectId().equals(id))) {
            return !uncertainObjects.contains(id);
        }
        Optional<QualifiedName> name = declaredName(id);
        if (effect instanceof Effects.CreateObject) {
            name = Optional.of(((Effects.CreateObject) effect).getShape().getName());
        }
        return name.isPresent() && overlay.containsKey(name.get()) && overlay.get(name.get()).getState() == OverlayEntry.State.DELETED;
    }

    boolean preconditionSatisfied(Effects.Precondition precondition) {
        if (!precondition.getObject().isPresent()) {
            return true;
        }
        ObjectId id = precondition.getObject().get();
        boolean exists = temporary.values().stream().anyMatch(entry -> entry.getObject().getObjectId().equals(id))
            || overlay.values().stream().anyMatch(entry -> entry.getEntry().isPresent()
                && entry.getEntry().get().getObject().getObjectId().equals(id));
        return precondition.getKind() == Effects.Precondition.Kind.OBJECT_PRESENT ? exists : !exists;
    }

    private void setVariable(Effects.SessionKey key, String value) {
        VariableKey variable = SessionCatalogConversions.variableKey(key);
        variables.put(variable, new VariableState(VariableState.Resolution.KNOWN, Optional.of(value), SourceRef.synthetic("session")));
        uncertainVariables.remove(variable);
    }

    private void put(CatalogEntry entry) {
        QualifiedName name = entry.getObject().getName();
        if (entry.getObject().getKind() == com.panfan.hivelineage.v2.metadata.ObjectDescriptor.Kind.TEMPORARY_TABLE) {
            temporary.put(name, entry);
        } else {
            overlay.put(name, OverlayEntry.upserted(entry));
        }
        uncertainObjects.remove(entry.getObject().getObjectId());
        declaredNames.put(entry.getObject().getObjectId(), name);
    }

    private void drop(ObjectId id) {
        Set<QualifiedName> removed = new LinkedHashSet<>();
        temporary.entrySet().removeIf(entry -> {
            boolean matches = entry.getValue().getObject().getObjectId().equals(id);
            if (matches) {
                removed.add(entry.getKey());
            }
            return matches;
        });
        for (Map.Entry<QualifiedName, OverlayEntry> entry : overlay.entrySet()) {
            if (entry.getValue().getEntry().isPresent() && entry.getValue().getEntry().get().getObject().getObjectId().equals(id)) {
                entry.setValue(OverlayEntry.deleted());
                removed.add(entry.getKey());
            }
        }
        removed.forEach(definitions::remove);
        versions.remove(id);
    }

    void rename(Effects.RenameObject effect) {
        Optional<CatalogEntry> found = temporary.values().stream()
            .filter(entry -> entry.getObject().getObjectId().equals(effect.getObjectId())).findFirst();
        if (!found.isPresent()) {
            found = overlay.values().stream().filter(entry -> entry.getEntry().isPresent())
                .map(entry -> entry.getEntry().get()).filter(entry -> entry.getObject().getObjectId().equals(effect.getObjectId())).findFirst();
        }
        if (found.isPresent()) {
            CatalogEntry original = found.get();
            boolean originallyUncertain = uncertainObjects.contains(effect.getObjectId());
            DataVersionState originalVersion = versions.get(effect.getObjectId());
            drop(effect.getObjectId());
            put(SessionCatalogConversions.renamed(original, effect.getNewName()));
            if (originallyUncertain) {
                uncertainObjects.add(effect.getObjectId());
                uncertain = true;
            }
            if (originalVersion != null) {
                versions.put(effect.getObjectId(), originallyUncertain
                    && originalVersion.getVersion().getState() != VersionRef.State.NOT_REQUESTED
                    ? new DataVersionState(VersionRef.unknown("RENAMED_OBJECT_STATE_UNCERTAIN"),
                        originalVersion.getPredecessors(), DataVersionState.Basis.UNKNOWN) : originalVersion);
            }
            original.getDefinition().ifPresent(definition -> definitions.put(effect.getNewName(), definition));
        } else {
            Optional<QualifiedName> oldName = effect.getPreviousName().isPresent() ? effect.getPreviousName() : declaredName(effect.getObjectId());
            oldName.ifPresent(name -> overlay.put(name, OverlayEntry.deleted()));
            overlay.put(effect.getNewName(), OverlayEntry.unknown(new DiagnosticBag(Collections.singletonList(
                new Diagnostic(AnalysisStage.BINDING, "RENAMED_SCHEMA_UNKNOWN", "External object schema was not bound", SourceRef.synthetic("session"))))));
            markUncertain(effect);
        }
    }

    private void register(LogicalDefinition definition, Effects.RegisterDefinition effect) {
        definitions.put(effect.getName(), definition);
        CatalogEntry entry = temporary.get(effect.getName());
        if (entry != null) {
            temporary.put(effect.getName(), new CatalogEntry(entry.getObject(), entry.getLookupNames(), entry.getSchema(), Optional.of(definition)));
        } else if (overlay.containsKey(effect.getName()) && overlay.get(effect.getName()).getEntry().isPresent()) {
            entry = overlay.get(effect.getName()).getEntry().get();
            overlay.put(effect.getName(), OverlayEntry.upserted(new CatalogEntry(entry.getObject(), entry.getLookupNames(), entry.getSchema(), Optional.of(definition))));
        }
    }

    private void taintVariable(Effects.SessionKey key) {
        VariableKey variable = SessionCatalogConversions.variableKey(key);
        uncertainVariables.add(variable);
        variables.put(variable, new VariableState(VariableState.Resolution.UNRESOLVED, Optional.empty(), SourceRef.synthetic("session")));
    }

    void markUncertain(BoundEffect effect) {
        uncertain = true;
        if (effect instanceof Effects.UnknownEffect) {
            Effects.UnknownEffect unknown = (Effects.UnknownEffect) effect;
            uncertainObjects.addAll(unknown.getAffectedObjects());
            unknown.getAffectedKeys().forEach(this::taintVariable);
            catalogUncertain |= unknown.getCatalogMayChange();
        } else if (effect instanceof Effects.SetDatabase) {
            databaseUncertain = true;
        } else if (effect instanceof Effects.SetVariable) {
            taintVariable(((Effects.SetVariable) effect).getKey());
        } else if (effect instanceof Effects.SetOption) {
            taintVariable(((Effects.SetOption) effect).getKey());
        } else {
            effect.getPrecondition().getObject().ifPresent(uncertainObjects::add);
            if (effect instanceof Effects.CreateObject) {
                Effects.ObjectShape shape = ((Effects.CreateObject) effect).getShape();
                declaredNames.put(shape.getObjectId(), shape.getName());
                uncertainObjects.add(shape.getObjectId());
            } else if (effect instanceof Effects.AlterObject) {
                uncertainObjects.add(((Effects.AlterObject) effect).getReplacement().getObjectId());
            } else if (effect instanceof Effects.DropObject) {
                uncertainObjects.add(((Effects.DropObject) effect).getObjectId());
            } else if (effect instanceof Effects.RenameObject) {
                uncertainObjects.add(((Effects.RenameObject) effect).getObjectId());
            } else if (effect instanceof Effects.RegisterDefinition) {
                ((Effects.RegisterDefinition) effect).getPhysicalObject().ifPresent(uncertainObjects::add);
            }
        }
        for (ObjectId id : uncertainObjects) {
            Optional<QualifiedName> name = declaredName(id);
            if (name.isPresent() && !temporary.containsKey(name.get()) && !overlay.containsKey(name.get())) {
                overlay.put(name.get(), OverlayEntry.unknown(DiagnosticBag.empty()));
            }
        }
        diagnostics.add(new Diagnostic(AnalysisStage.BINDING, "SESSION_EFFECT_UNCERTAIN",
            "Effect cannot be committed with certainty: " + effect.getKind(), SourceRef.synthetic("session")));
    }

    private final SessionSnapshot before;
    private final SessionEffectPlan plan;

    boolean creationBranchApplied(BoundEffect effect) {
        return effect instanceof Effects.RegisterDefinition
            && ((Effects.RegisterDefinition) effect).getPhysicalObject().isPresent()
            && appliedCreateBranches.contains(((Effects.RegisterDefinition) effect).getPhysicalObject().get());
    }

    void suppressCreateVersion(BoundEffect effect) {
        if (effect instanceof Effects.CreateObject) {
            suppressedVersionWrites.add(((Effects.CreateObject) effect).getShape().getObjectId());
        }
    }

    void setDatabase(Effects.SetDatabase effect) {
        database = Optional.of(effect.getDatabase());
        databaseUncertain = false;
    }

    void setVariable(Effects.SetVariable effect) {
        setVariable(effect.getKey(), effect.getValue());
    }

    void setOption(Effects.SetOption effect) {
        setVariable(effect.getKey(), effect.getValue());
    }

    void create(Effects.CreateObject effect) {
        put(SessionCatalogConversions.entry(effect.getShape(), before));
        appliedCreateBranches.add(effect.getShape().getObjectId());
    }

    void alter(Effects.AlterObject effect) {
        put(SessionCatalogConversions.entry(effect.getReplacement(), before));
    }

    void drop(Effects.DropObject effect) {
        drop(effect.getObjectId());
        suppressedVersionWrites.add(effect.getObjectId());
        if (effect.getObjectKind() != Effects.ObjectKind.TEMPORARY_TABLE) {
            Optional<QualifiedName> name = effect.getName().isPresent() ? effect.getName() : declaredName(effect.getObjectId());
            if (!name.isPresent()) {
                markUncertain(effect);
                return;
            }
            overlay.put(name.get(), OverlayEntry.deleted());
            definitions.remove(name.get());
        }
        uncertainObjects.remove(effect.getObjectId());
    }

    void register(Effects.RegisterDefinition effect) {
        if (effect.getPhysicalObject().isPresent()) {
            ObjectId object = effect.getPhysicalObject().get();
            if (suppressedVersionWrites.contains(object)) {
                return;
            }
            if (uncertainObjects.contains(object)) {
                markUncertain(effect);
                return;
            }
        }
        register(SessionCatalogConversions.definition(effect, plan, before), effect);
    }

    SessionCommitResult commit() {
        SessionCatalogConversions.updateVersions(versions, plan, uncertainObjects, suppressedVersionWrites);
        DiagnosticBag bag = new DiagnosticBag(DiagnosticBag.distinct(diagnostics));
        UncertaintyState uncertainty = new UncertaintyState(uncertainObjects, uncertainVariables,
            catalogUncertain, databaseUncertain, bag);
        SessionSnapshot after = new SessionSnapshot(before.getInitialContext(), database, variables,
            temporary, overlay, definitions, versions, uncertainty, before.getRevision() + 1L);
        return new SessionCommitResult(uncertain ? CommitOutcome.UNCERTAIN : CommitOutcome.COMMITTED, after, bag);
    }
}
