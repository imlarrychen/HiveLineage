package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.List;

public final class JoinPresentation {
    private final int typeCode;
    private final String typeName;
    private final List<QualifiedName> directObjects;
    private final List<ColumnAddress> directColumns;
    private final List<QualifiedName> physicalTables;
    private final List<ColumnAddress> physicalColumns;
    private final RenderedExpression expression;

    public JoinPresentation(int typeCode, String typeName, List<QualifiedName> directObjects,
                            List<ColumnAddress> directColumns, List<QualifiedName> physicalTables,
                            List<ColumnAddress> physicalColumns, RenderedExpression expression) {
        this.typeCode = ModelChecks.nonNegative(typeCode, "typeCode");
        this.typeName = ModelChecks.text(typeName, "typeName");
        this.directObjects = ModelCollections.immutableList(directObjects);
        this.directColumns = ModelCollections.immutableList(directColumns);
        this.physicalTables = ModelCollections.immutableList(physicalTables);
        this.physicalColumns = ModelCollections.immutableList(physicalColumns);
        this.expression = ModelChecks.value(expression, "expression");
    }

    public int getTypeCode() {
        return typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public List<QualifiedName> getDirectObjects() {
        return directObjects;
    }

    public List<ColumnAddress> getDirectColumns() {
        return directColumns;
    }

    public List<QualifiedName> getPhysicalTables() {
        return physicalTables;
    }

    public List<ColumnAddress> getPhysicalColumns() {
        return physicalColumns;
    }

    public RenderedExpression getExpression() {
        return expression;
    }
}
