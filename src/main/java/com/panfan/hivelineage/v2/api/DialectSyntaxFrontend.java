package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.dialect.DialectFactory;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.lexical.LexResult;
import com.panfan.hivelineage.v2.lexical.LexicalOptions;
import com.panfan.hivelineage.v2.syntax.ParseResult;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;

import java.util.ArrayList;
import java.util.List;

public final class DialectSyntaxFrontend<P> implements SyntaxFrontend {
    private final DialectFactory<P> factory;

    public DialectSyntaxFrontend(DialectFactory<P> factory) {
        this.factory = java.util.Objects.requireNonNull(factory, "factory");
    }

    @Override
    public SyntaxResult parse(ExpandedUnit unit, DialectProfile dialect) {
        if (!factory.getProfile().getDialectId().equals(dialect.getDialectId())
                || !factory.getProfile().getParserVersion().equals(dialect.getParserVersion())) {
            throw new IllegalArgumentException("requested dialect does not match frontend factory");
        }
        LexResult lexed = factory.createLexer().tokenize(unit, new LexicalOptions(dialect, true));
        List<Diagnostic> diagnostics = new ArrayList<>(lexed.getDiagnostics());
        if (lexed.getUnit() == null || lexed.getOutcome() == StageOutcome.FAILED) {
            return new SyntaxResult(null, DiagnosticBag.distinct(diagnostics), StageOutcome.FAILED);
        }
        SyntaxOptions options = new SyntaxOptions(dialect, true);
        ParseResult<P> parsed = factory.createParser().parse(lexed.getUnit(), options);
        diagnostics.addAll(parsed.getDiagnostics());
        if (parsed.getArtifact() == null || parsed.getOutcome() == StageOutcome.FAILED) {
            return new SyntaxResult(null, DiagnosticBag.distinct(diagnostics), StageOutcome.FAILED);
        }
        SyntaxResult normalized = factory.createNormalizer().normalize(parsed.getArtifact(), options);
        diagnostics.addAll(normalized.getDiagnostics());
        return new SyntaxResult(normalized.getStatement(), DiagnosticBag.distinct(diagnostics),
                combinedOutcome(lexed.getOutcome(), parsed.getOutcome(), normalized.getOutcome()));
    }

    private StageOutcome combinedOutcome(StageOutcome lexical, StageOutcome parsed, StageOutcome normalized) {
        if (normalized == StageOutcome.FAILED) {
            return StageOutcome.FAILED;
        }
        if (lexical == StageOutcome.PARTIAL || parsed == StageOutcome.PARTIAL || normalized == StageOutcome.PARTIAL) {
            return StageOutcome.PARTIAL;
        }
        return StageOutcome.SUCCESS;
    }
}
