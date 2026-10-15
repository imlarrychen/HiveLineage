package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
/** Pure catalog model conversions; traversal and version policies belong to transaction participants. */
final class SessionCatalogConversions {
    private SessionCatalogConversions() {
    }

    static VariableKey variableKey(Effects.SessionKey key) {
        VariableKey.Namespace namespace;
        switch (key.getNamespace()) {
            case HIVEVAR:
                namespace = VariableKey.Namespace.HIVEVAR;
                break;
            case HIVECONF:
                namespace = VariableKey.Namespace.HIVECONF;
                break;
            case SYSTEM_PROPERTY:
                namespace = VariableKey.Namespace.SYSTEM;
                break;
            case ENVIRONMENT:
                namespace = VariableKey.Namespace.ENVIRONMENT;
                break;
            default:
                namespace = VariableKey.Namespace.UNQUALIFIED;
        }
        return new VariableKey(namespace, key.getKey());
    }

    static CatalogEntry entry(Effects.ObjectShape shape, SessionSnapshot session) {
        MetadataProvenance provenance = provenance(session);
        ObjectDescriptor.Kind kind = shape.getKind() == Effects.ObjectKind.TEMPORARY_TABLE ? ObjectDescriptor.Kind.TEMPORARY_TABLE
                : shape.getKind() == Effects.ObjectKind.VIEW ? ObjectDescriptor.Kind.VIEW : ObjectDescriptor.Kind.TABLE;
        ObjectDescriptor object = new ObjectDescriptor(shape.getObjectId(), shape.getName(), kind,
                Optional.<DefinitionRef>empty(), provenance, MetadataVersion.unknown());
        return new CatalogEntry(object, shape.getLookupNames(),
                Optional.of(schema(shape.getSchema(), provenance, shape.getPartitionColumns())), Optional.<LogicalDefinition>empty());
    }

    static RelationSchema schema(OutputSchema schema, MetadataProvenance provenance) {
        return schema(schema, provenance, Collections.<String>emptyList());
    }

    private static RelationSchema schema(OutputSchema schema, MetadataProvenance provenance, List<String> partitions) {
        List<ColumnMetadata> columns = new ArrayList<>();
        for (OutputSegment segment : schema.getSegments()) {
            if (segment instanceof KnownOutputSegment) {
                for (OutputSlot slot : ((KnownOutputSegment) segment).getSlots()) {
                    columns.add(new ColumnMetadata(slot.getDisplayName(), columns.size(), slot.getType(),
                            partitions.contains(slot.getDisplayName()) ? ColumnMetadata.Role.PARTITION : ColumnMetadata.Role.DATA,
                            ColumnMetadata.Nullability.UNKNOWN));
                }
            }
        }
        return new RelationSchema(schema.getState(), columns, provenance, MetadataVersion.unknown());
    }

    static LogicalDefinition definition(Effects.RegisterDefinition effect, SessionEffectPlan plan, SessionSnapshot session) {
        Optional<DefinitionTemplate> template = Optional.ofNullable(plan.getDefinitions().get(effect.getDefinitionId()));
        if (!template.isPresent() && plan.getBoundUnit().isPresent()) {
            BoundRelation relation = plan.getBoundUnit().get().getRelations().stream()
                    .filter(node -> node.getId().equals(effect.getRelationRoot())).findFirst().orElse(null);
            if (relation != null) {
                template = Optional.of(new PreparedDefinition(effect.getDefinitionId(), Optional.<SyntaxTemplate>empty(),
                        Optional.of(relation), SourceRef.synthetic("session:" + effect.getDefinitionId().getValue()), plan.getBoundUnit()));
            }
        }
        InitialSessionContext initial = session.getInitialContext();
        DefinitionContext context = new DefinitionContext(session.getCurrentDatabase(), Collections.<String, String>emptyMap(),
                "hive", "4.0.1", "v2", new MetadataReadContext(initial.getWorkflowId(), initial.getClusterName(), Optional.<String>empty(),
                Collections.<String, String>emptyMap()));
        DefinitionRef reference = new DefinitionRef(effect.getDefinitionId(), Optional.<String>empty(), context, provenance(session),
                MetadataVersion.unknown(), template.isPresent() ? template.get().getSource() : SourceRef.synthetic("session"));
        LogicalDefinition.Kind kind = !effect.getPhysicalObject().isPresent() ? LogicalDefinition.Kind.LEGACY_CTE
                : session.getTemporaryCatalog().containsKey(effect.getName()) || plan.getEffects().stream().anyMatch(proposed -> proposed instanceof Effects.CreateObject
                && ((Effects.CreateObject) proposed).getShape().getName().equals(effect.getName())
                && ((Effects.CreateObject) proposed).getShape().getKind() == Effects.ObjectKind.TEMPORARY_TABLE)
                  ? LogicalDefinition.Kind.TEMPORARY_RELATION : LogicalDefinition.Kind.VIEW;
        return new LogicalDefinition(kind, reference, template);
    }

    private static MetadataProvenance provenance(SessionSnapshot session) {
        return new MetadataProvenance(session.getInitialContext().getSessionId(), MetadataProvenance.Origin.SESSION_DEFINED,
                Optional.<String>empty(), Optional.<Long>empty());
    }
}
