package com.panfan.hivelineage.v2.syntax;

public final class DelimitedRowFormatSyntax {
    private final String fieldsTerminatedBy;
    private final String escapedBy;
    private final String collectionItemsTerminatedBy;
    private final String mapKeysTerminatedBy;
    private final String linesTerminatedBy;
    private final String nullDefinedAs;

    public DelimitedRowFormatSyntax(
            String fieldsTerminatedBy,
            String escapedBy,
            String collectionItemsTerminatedBy,
            String mapKeysTerminatedBy,
            String linesTerminatedBy,
            String nullDefinedAs) {
        this.fieldsTerminatedBy = fieldsTerminatedBy;
        this.escapedBy = escapedBy;
        this.collectionItemsTerminatedBy = collectionItemsTerminatedBy;
        this.mapKeysTerminatedBy = mapKeysTerminatedBy;
        this.linesTerminatedBy = linesTerminatedBy;
        this.nullDefinedAs = nullDefinedAs;
    }

    public String getFieldsTerminatedBy() {
        return fieldsTerminatedBy;
    }

    public String getEscapedBy() {
        return escapedBy;
    }

    public String getCollectionItemsTerminatedBy() {
        return collectionItemsTerminatedBy;
    }

    public String getMapKeysTerminatedBy() {
        return mapKeysTerminatedBy;
    }

    public String getLinesTerminatedBy() {
        return linesTerminatedBy;
    }

    public String getNullDefinedAs() {
        return nullDefinedAs;
    }
}
