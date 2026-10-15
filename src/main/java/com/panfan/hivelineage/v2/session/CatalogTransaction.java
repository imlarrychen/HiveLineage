package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Owns object names, aliases and definition synchronization within one unpublished transaction. */
final class CatalogTransaction {
    private final Map<QualifiedName, CatalogEntry> temporary;
    private final Map<QualifiedName, OverlayEntry> overlay;
    private final Map<QualifiedName, LogicalDefinition> definitions;
    private final Map<ObjectId, QualifiedName> declaredNames = new LinkedHashMap<>();
    private final Set<ObjectId> appliedCreateBranches = new LinkedHashSet<>();

    CatalogTransaction(SessionSnapshot before, SessionEffectPlan plan) {
        temporary = new LinkedHashMap<>(before.getTemporaryCatalog());
        overlay = new LinkedHashMap<>(before.getPersistentOverlay());
        definitions = new LinkedHashMap<>(before.getScriptDefinitions());
        temporary.values().forEach(this::declare);
        overlay.values().forEach(entry -> entry.getEntry().ifPresent(this::declare));
        plan.getBoundUnit().ifPresent(unit -> unit.getObjects().values().forEach(object -> declare(object.getId(), object.getName())));
    }

    boolean preconditionKnown(BoundEffect effect, UncertaintyTracker uncertainty) {
        if (!effect.getPrecondition().getObject().isPresent()) {
            return effect.getPrecondition().getKind() == Effects.Precondition.Kind.ALWAYS;
        }
        ObjectId id = effect.getPrecondition().getObject().get();
        if (find(id).isPresent()) {
            return !uncertainty.contains(id);
        }
        Optional<QualifiedName> name = effect instanceof Effects.CreateObject
                ? Optional.of(((Effects.CreateObject) effect).getShape().getName()) : declaredName(id);
        return name.isPresent() && overlay.containsKey(name.get()) && overlay.get(name.get()).getState() == OverlayEntry.State.DELETED;
    }

    boolean preconditionSatisfied(Effects.Precondition precondition) {
        if (!precondition.getObject().isPresent()) {
            return true;
        }
        boolean exists = find(precondition.getObject().get()).isPresent();
        return precondition.getKind() == Effects.Precondition.Kind.OBJECT_PRESENT ? exists : !exists;
    }

    void put(CatalogEntry entry) {
        QualifiedName name = entry.getObject().getName();
        if (entry.getObject().getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE) {
            temporary.put(name, entry);
        } else {
            overlay.put(name, OverlayEntry.upserted(entry));
        }
        declare(entry);
    }

    void create(CatalogEntry entry) {
        put(entry);
        appliedCreateBranches.add(entry.getObject().getObjectId());
    }

    boolean creationBranchApplied(BoundEffect effect) {
        return effect instanceof Effects.RegisterDefinition
                && ((Effects.RegisterDefinition) effect).getPhysicalObject().isPresent()
                && appliedCreateBranches.contains(((Effects.RegisterDefinition) effect).getPhysicalObject().get());
    }

    boolean drop(Effects.DropObject effect) {
        removeNames(effect.getObjectId());
        if (effect.getObjectKind() == Effects.ObjectKind.TEMPORARY_TABLE) {
            return true;
        }
        Optional<QualifiedName> name = effect.getName().isPresent() ? effect.getName() : declaredName(effect.getObjectId());
        if (!name.isPresent()) {
            return false;
        }
        overlay.put(name.get(), OverlayEntry.deleted());
        definitions.remove(name.get());
        return true;
    }

    /** Relocation updates only catalog names; version and uncertainty objects never leave their owners. */
    boolean rename(Effects.RenameObject effect) {
        Optional<CatalogEntry> found = find(effect.getObjectId());
        if (found.isPresent()) {
            CatalogEntry original = found.get();
            removeNames(effect.getObjectId());
            put(renamedEntry(original, effect.getNewName()));
            original.getDefinition().ifPresent(definition -> definitions.put(effect.getNewName(), definition));
            return true;
        }
        Optional<QualifiedName> oldName = effect.getPreviousName().isPresent() ? effect.getPreviousName() : declaredName(effect.getObjectId());
        oldName.ifPresent(name -> overlay.put(name, OverlayEntry.deleted()));
        overlay.put(effect.getNewName(), OverlayEntry.unknown(new DiagnosticBag(Collections.singletonList(
                new Diagnostic(AnalysisStage.BINDING, "RENAMED_SCHEMA_UNKNOWN", "External object schema was not bound", SourceRef.synthetic("session"))))));
        return false;
    }

    void register(LogicalDefinition definition, Effects.RegisterDefinition effect) {
        definitions.put(effect.getName(), definition);
        CatalogEntry entry = temporary.get(effect.getName());
        if (entry != null) {
            temporary.put(effect.getName(), withDefinition(entry, definition));
        } else if (overlay.containsKey(effect.getName()) && overlay.get(effect.getName()).getEntry().isPresent()) {
            entry = overlay.get(effect.getName()).getEntry().get();
            overlay.put(effect.getName(), OverlayEntry.upserted(withDefinition(entry, definition)));
        }
    }

    void retainUnknownObjects(Set<ObjectId> objects) {
        for (ObjectId id : objects) {
            Optional<QualifiedName> name = declaredName(id);
            if (name.isPresent() && !temporary.containsKey(name.get()) && !overlay.containsKey(name.get())) {
                overlay.put(name.get(), OverlayEntry.unknown(DiagnosticBag.empty()));
            }
        }
    }

    void declare(ObjectId id, QualifiedName name) {
        declaredNames.put(id, name);
    }

    Map<QualifiedName, CatalogEntry> temporary() {
        return Collections.unmodifiableMap(temporary);
    }

    Map<QualifiedName, OverlayEntry> overlay() {
        return Collections.unmodifiableMap(overlay);
    }

    Map<QualifiedName, LogicalDefinition> definitions() {
        return Collections.unmodifiableMap(definitions);
    }

    private void declare(CatalogEntry entry) {
        declare(entry.getObject().getObjectId(), entry.getObject().getName());
    }

    private Optional<QualifiedName> declaredName(ObjectId id) {
        return Optional.ofNullable(declaredNames.get(id));
    }

    private Optional<CatalogEntry> find(ObjectId id) {
        Optional<CatalogEntry> found = temporary.values().stream()
                .filter(entry -> entry.getObject().getObjectId().equals(id)).findFirst();
        if (found.isPresent()) {
            return found;
        }
        return overlay.values().stream().filter(entry -> entry.getEntry().isPresent())
                .map(entry -> entry.getEntry().get()).filter(entry -> entry.getObject().getObjectId().equals(id)).findFirst();
    }

    private void removeNames(ObjectId id) {
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
    }

    private CatalogEntry withDefinition(CatalogEntry entry, LogicalDefinition definition) {
        return new CatalogEntry(entry.getObject(), entry.getLookupNames(), entry.getSchema(), Optional.of(definition));
    }

    private CatalogEntry renamedEntry(CatalogEntry original, QualifiedName name) {
        ObjectDescriptor old = original.getObject();
        ObjectDescriptor replacement = new ObjectDescriptor(old.getObjectId(), name, old.getKind(), old.getDefinition(),
                old.getProvenance(), old.getVersion());
        Set<QualifiedName> lookupNames = new LinkedHashSet<>();
        lookupNames.add(name);
        if (old.getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE) {
            String simpleName = name.getParts().get(name.getParts().size() - 1);
            for (QualifiedName alias : original.getLookupNames()) {
                List<String> parts = new ArrayList<>(alias.getParts());
                parts.set(parts.size() - 1, simpleName);
                lookupNames.add(new QualifiedName(parts));
            }
        }
        return new CatalogEntry(replacement, new ArrayList<>(lookupNames), original.getSchema(), original.getDefinition());
    }
}
