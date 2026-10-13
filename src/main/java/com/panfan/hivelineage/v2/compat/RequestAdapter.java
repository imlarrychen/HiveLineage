package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.api.V2AnalyzeRequest;

public interface RequestAdapter {
    V2AnalyzeRequest adapt(LineageAnalyzeRequest request);
}
