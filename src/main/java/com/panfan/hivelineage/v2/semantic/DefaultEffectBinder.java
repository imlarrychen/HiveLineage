package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Converts structured commands/catalog declarations to proposals without applying them. */
public final class DefaultEffectBinder implements EffectBinder {
    @Override
    public EffectBindingResult bind(SyntaxStatement statement, BindingContext context, BindingDemand demand) {
        List<BoundEffect> effects = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Effects.Precondition always = new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
        if (statement instanceof CommandStatementSyntax) {
            com.panfan.hivelineage.v2.syntax.CommandSyntax command = ((CommandStatementSyntax) statement).getCommand();
            NodeId id = new NodeId(statement.getNodeId().getValue() + "/session-effect");
            if (command instanceof UseCommandSyntax) {
                QualifiedName database = SemanticNames.name(((UseCommandSyntax) command).getDatabase());
                if (database.getDisplayName().equalsIgnoreCase(context.getContract().getFallbackVariableValue())) {
                    diagnostics.add(new Diagnostic(AnalysisStage.BINDING, "INVALID_USE_DATABASE",
                        "A fallback variable cannot set the current database", statement.getSourceRef()));
                } else {
                    effects.add(new Effects.SetDatabase(id, Effects.Certainty.CERTAIN, always, database));
                }
            } else if (command instanceof SetCommandSyntax) {
                SetCommandSyntax set = (SetCommandSyntax) command;
                if (set.getValue() != null) {
                    String namespace = set.getNamespace().toLowerCase(Locale.ROOT);
                    Effects.Namespace kind = namespace.isEmpty() || "unqualified".equals(namespace) ? Effects.Namespace.CONFIGURATION
                        : "hiveconf".equals(namespace) ? Effects.Namespace.HIVECONF
                        : "system".equals(namespace) ? Effects.Namespace.SYSTEM_PROPERTY
                        : "env".equals(namespace) ? Effects.Namespace.ENVIRONMENT : Effects.Namespace.HIVEVAR;
                    effects.add(new Effects.SetVariable(id, Effects.Certainty.CERTAIN, always,
                        new Effects.SessionKey(kind, set.getKey()), set.getValue()));
                }
            }
        }
        if (statement instanceof DefinitionStatementSyntax
            && context.getContract().getCapabilities().getCapabilities().contains(AnalysisCapability.CATALOG_SIMULATION)) {
            DefinitionBodySyntax body = ((DefinitionStatementSyntax) statement).getDefinition();
            if (body.getQuery() == null) {
                QualifiedName name = SemanticNames.qualify(body.getName(), context, body.getOptions().isTemporary());
                ObjectId object = new ObjectId(context.getMetadataContext().getSourceId() + ":" + context.getMetadataContext().getClusterName()
                    + ":" + name.getDisplayName());
                Effects.ObjectKind kind = body.getOptions().isTemporary() ? Effects.ObjectKind.TEMPORARY_TABLE
                    : body.getKind() == DefinitionKindSyntax.CREATE_VIEW || body.getKind() == DefinitionKindSyntax.DROP_VIEW
                    ? Effects.ObjectKind.VIEW : Effects.ObjectKind.TABLE;
                Effects.Precondition precondition = new Effects.Precondition(
                    body.getOptions().isIfNotExists() ? Effects.Precondition.Kind.OBJECT_ABSENT
                        : body.getOptions().isIfExists() ? Effects.Precondition.Kind.OBJECT_PRESENT : Effects.Precondition.Kind.ALWAYS,
                    body.getOptions().isIfNotExists() || body.getOptions().isIfExists() ? object : null);
                Effects.Certainty certainty = Effects.Certainty.CERTAIN;
                if (body.getOptions().isIfNotExists()) {
                    LookupStatus existing = new ObjectExistenceResolver(context).lookup(name, object, body.getOptions().isTemporary());
                    if (existing == LookupStatus.FOUND) {
                        return new EffectBindingResult(effects, new DiagnosticBag(diagnostics));
                    }
                    if (existing == LookupStatus.NOT_FOUND) {
                        precondition = always;
                    } else {
                        certainty = Effects.Certainty.CONDITIONAL;
                    }
                }
                NodeId id = new NodeId(statement.getNodeId().getValue() + "/catalog-effect");
                if (body.getKind() == DefinitionKindSyntax.DROP_TABLE || body.getKind() == DefinitionKindSyntax.DROP_VIEW) {
                    effects.add(new Effects.DropObject(id, Effects.Certainty.CERTAIN, precondition, object, kind, name));
                } else if (body.getKind() == DefinitionKindSyntax.CREATE_TABLE || body.getKind() == DefinitionKindSyntax.CREATE_VIEW) {
                    List<OutputSlot> slots = new ArrayList<>();
                    List<ColumnDefinitionSyntax> columns = new ArrayList<>(body.getSchema().getColumns());
                    columns.addAll(body.getSchema().getPartitionColumns());
                    int position = 0;
                    for (ColumnDefinitionSyntax column : columns) {
                        String columnName = SemanticNames.normalize(column.getName().getValue());
                        FieldIdentity field = new FieldIdentity(object.getValue() + "/field/" + columnName, columnName);
                        slots.add(new OutputSlot(new SlotId(id.getValue() + "/column-" + position++), columnName,
                            SemanticTypes.declared(column.getType()), field));
                    }
                    effects.add(new Effects.CreateObject(id, certainty, precondition,
                        new Effects.ObjectShape(object, name, kind, OutputSchema.known(slots), VersionRef.notRequested(),
                            body.getOptions().isTemporary() ? SemanticNames.temporaryLookupNames(body.getName(), context)
                                : Collections.singletonList(name))));
                } else if (body.getKind() == DefinitionKindSyntax.ALTER_TABLE) {
                    effects.addAll(new CatalogActionBinder(context).alter(body, name, object, id, precondition));
                } else if (body.getKind() == DefinitionKindSyntax.TRUNCATE_TABLE) {
                    effects.add(new Effects.UnknownEffect(id, Effects.Certainty.UNKNOWN, precondition,
                        Collections.singletonList(object), Collections.<Effects.SessionKey>emptyList(), false, "TRUNCATE_VERSION_UNKNOWN"));
                }
            }
        }
        return new EffectBindingResult(effects, new DiagnosticBag(diagnostics));
    }
}
