package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

public class SnapshotMetadataServicesRegressionTest {
    private final MetadataReadContext context = new MetadataReadContext("source", "cluster", Optional.empty(), Collections.emptyMap());
    private final QualifiedName name = new QualifiedName(Arrays.asList("db", "t"));
    private final DiagnosticBag diagnostics = new DiagnosticBag(Collections.singletonList(
            new Diagnostic(AnalysisStage.METADATA, "RECORDED_FAILURE", "recorded failure", SourceRef.synthetic("metadata"))));

    @Test
    public void bundledAbsentStatusesAndDiagnosticsSurviveFacetDerivation() {
        for (LookupStatus status : new LookupStatus[]{LookupStatus.UNKNOWN, LookupStatus.NOT_FOUND,
                LookupStatus.CAPABILITY_MISSING, LookupStatus.ACCESS_ERROR}) {
            SnapshotMetadataServices services = services(relation(new LookupResult<RelationMetadata>(status, Optional.empty(), diagnostics)));
            Assert.assertEquals(status, services.lookupObject(name, context).getStatus());
            Assert.assertEquals(status, services.lookupSchema(name, context).getStatus());
            Assert.assertEquals("RECORDED_FAILURE", services.lookupObject(name, context).getDiagnostics().getDiagnostics().get(0).getCode());
        }
    }

    @Test
    public void recordedFacetUnknownIsNotPromotedByALaterBundledValue() {
        RecordedLookup<ObjectDescriptor> object = new RecordedLookup<ObjectDescriptor>(key(MetadataFacet.OBJECT_DESCRIPTOR),
                ObjectDescriptor.class, LookupResult.unknown(diagnostics), ProviderCapabilities.LookupShape.FACET_LOOKUP);
        SnapshotMetadataServices services = services(object, relation(LookupResult.found(value())));
        Assert.assertEquals(LookupStatus.UNKNOWN, services.lookupObject(name, context).getStatus());
        Assert.assertEquals("RECORDED_FAILURE", services.lookupObject(name, context).getDiagnostics().getDiagnostics().get(0).getCode());
    }

    @Test
    public void legacyReplayRaisesRecordedAccessFailuresRatherThanReturningMissing() {
        SnapshotMetadataServices services = services(relation(LookupResult.accessError(diagnostics)));
        try {
            services.asLegacyProvider().getTableMetadata(TableIdentifier.of("cluster", "db", "t"));
            Assert.fail("recorded access failure must be reproduced");
        } catch (IllegalStateException failure) {
            Assert.assertEquals("recorded failure", failure.getMessage());
        }
    }

    @Test
    public void failedFacetsPreserveTheirStatusAndEvidenceWhenComposingRelationMetadata() {
        RecordedLookup<ObjectDescriptor> object = new RecordedLookup<ObjectDescriptor>(key(MetadataFacet.OBJECT_DESCRIPTOR),
                ObjectDescriptor.class, LookupResult.accessError(diagnostics));
        RecordedLookup<RelationSchema> schema = new RecordedLookup<RelationSchema>(key(MetadataFacet.RELATION_SCHEMA),
                RelationSchema.class, LookupResult.found(value().getSchema()));
        SnapshotMetadataServices services = services(object, schema);
        Assert.assertEquals(LookupStatus.ACCESS_ERROR, services.lookupRelation(name, context).getStatus());
        Assert.assertEquals("RECORDED_FAILURE", services.lookupRelation(name, context).getDiagnostics().getDiagnostics().get(0).getCode());
    }

    @Test
    public void extraReplayReadsAreExplicitAndCannotReachALiveProvider() {
        SnapshotMetadataServices services = services();
        Assert.assertEquals(LookupStatus.UNKNOWN, services.lookupObject(name, context).getStatus());
        Assert.assertEquals("METADATA_REPLAY_MISS", services.lookupObject(name, context).getDiagnostics().getDiagnostics().get(0).getCode());
    }

    @Test
    public void lookupShapeIsRetainedForEachRecordedFacet() {
        QualifiedName light = new QualifiedName(Arrays.asList("db", "light"));
        RecordedLookup<ObjectDescriptor> facet = new RecordedLookup<ObjectDescriptor>(
                new MetadataCacheKey(context, light, MetadataFacet.OBJECT_DESCRIPTOR), ObjectDescriptor.class,
                LookupResult.unknown(DiagnosticBag.empty()), ProviderCapabilities.LookupShape.FACET_LOOKUP);
        SnapshotMetadataServices services = services(facet, relation(LookupResult.found(value())));
        Assert.assertEquals(ProviderCapabilities.LookupShape.FACET_LOOKUP, services.getLookupShape(light, MetadataFacet.OBJECT_DESCRIPTOR));
        Assert.assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, services.getLookupShape(name, MetadataFacet.OBJECT_DESCRIPTOR));
    }

    private RelationMetadata value() {
        MetadataProvenance provenance = new MetadataProvenance("source", MetadataProvenance.Origin.TEST_FIXTURE,
                Optional.empty(), Optional.empty());
        ObjectDescriptor object = new ObjectDescriptor(new ObjectId("object"), name, ObjectDescriptor.Kind.TABLE,
                Optional.empty(), provenance, MetadataVersion.unknown());
        return new RelationMetadata(object, new RelationSchema(SchemaState.KNOWN, Collections.emptyList(), provenance, MetadataVersion.unknown()));
    }

    private RecordedLookup<RelationMetadata> relation(LookupResult<RelationMetadata> result) {
        return new RecordedLookup<RelationMetadata>(key(MetadataFacet.RELATION_METADATA), RelationMetadata.class, result,
                ProviderCapabilities.LookupShape.BUNDLED_LOOKUP);
    }

    private MetadataCacheKey key(MetadataFacet facet) {
        return new MetadataCacheKey(context, name, facet);
    }

    private SnapshotMetadataServices services(RecordedLookup<?>... reads) {
        return new SnapshotMetadataServices(new MetadataSnapshot(context, Arrays.asList(reads)));
    }
}
