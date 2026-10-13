package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

public interface IrVerifier {
    VerificationResult verify(BoundAnalysisUnit unit, BindingDemand demand);
}
