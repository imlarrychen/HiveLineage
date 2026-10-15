package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;

import java.util.List;

public final class ColumnAddress {
    private final QualifiedName object;
    private final List<String> fieldPath;

    public ColumnAddress(QualifiedName object, List<String> fieldPath) {
        this.object = ModelChecks.value(object, "object");
        this.fieldPath = ModelCollections.immutableList(fieldPath);
        if (fieldPath.isEmpty()) {
            throw new IllegalArgumentException("column address requires a field path");
        }
        for (String part : this.fieldPath) {
            ModelChecks.text(part, "field path part");
        }
    }

    public QualifiedName getObject() {
        return object;
    }

    public List<String> getFieldPath() {
        return fieldPath;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ColumnAddress)) {
            return false;
        }
        ColumnAddress address = (ColumnAddress) other;
        return object.equals(address.object) && fieldPath.equals(address.fieldPath);
    }

    @Override
    public int hashCode() {
        return 31 * object.hashCode() + fieldPath.hashCode();
    }
}
