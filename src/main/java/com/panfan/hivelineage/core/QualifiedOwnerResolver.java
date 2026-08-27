package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class QualifiedOwnerResolver {
    private final List<DataObjectRef> inputObjects;
    private final Map<String, String> explicitAliases;

    QualifiedOwnerResolver(List<DataObjectRef> inputObjects, Map<String, String> explicitAliases) {
        this.inputObjects = inputObjects;
        this.explicitAliases = explicitAliases;
    }

    static QualifiedOwnerResolver fromAliases(
        Map<String, String> sourceAliases,
        Map<String, String> explicitAliases
    ) {
        List<DataObjectRef> inputs = new ArrayList<>();
        for (String source : new LinkedHashSet<String>(sourceAliases.values())) {
            inputs.add(new DataObjectRef(source));
        }
        return new QualifiedOwnerResolver(inputs, explicitAliases);
    }

    String resolve(String owner) {
        if (owner == null) {
            return inputObjects.size() == 1 ? inputObjects.get(0).getQualifiedName() : null;
        }
        String normalizedOwner = owner.toLowerCase(Locale.ROOT);
        for (DataObjectRef inputObject : inputObjects) {
            if (normalizedOwner.equals(inputObject.getQualifiedName().toLowerCase(Locale.ROOT))) {
                return inputObject.getQualifiedName();
            }
        }
        String aliasTarget = explicitAliases.get(normalizedOwner);
        if (aliasTarget != null) {
            for (DataObjectRef inputObject : inputObjects) {
                if (aliasTarget.equalsIgnoreCase(inputObject.getQualifiedName())) {
                    return inputObject.getQualifiedName();
                }
            }
        }
        List<String> simpleMatches = new ArrayList<>();
        for (DataObjectRef inputObject : inputObjects) {
            String qualifiedName = inputObject.getQualifiedName();
            String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            if (normalizedOwner.equals(simpleName.toLowerCase(Locale.ROOT))) {
                simpleMatches.add(qualifiedName);
            }
        }
        return simpleMatches.size() == 1 ? simpleMatches.get(0) : null;
    }
}
