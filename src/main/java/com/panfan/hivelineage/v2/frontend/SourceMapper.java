package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

/**
 * External range lookup; source attachments never drive business source inference.
 */
public interface SourceMapper {
    SourceRef mapToOriginal(SourceMap sourceMap, SourceSpan expandedSpan);
}
