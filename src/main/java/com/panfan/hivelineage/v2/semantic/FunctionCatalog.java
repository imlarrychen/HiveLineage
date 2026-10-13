package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.Optional;

/** Provides declared summaries only, never loads or executes a UDF. */
public interface FunctionCatalog {
    Optional<FunctionDescriptor> find(QualifiedName name, String semanticVersion);
}
