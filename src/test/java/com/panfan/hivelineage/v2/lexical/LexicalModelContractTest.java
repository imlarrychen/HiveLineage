package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnitKind;
import com.panfan.hivelineage.v2.frontend.SourceMap;
import com.panfan.hivelineage.v2.frontend.SourceMapEntry;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.frontend.VariableEvidence;
import com.panfan.hivelineage.v2.syntax.ParseResult;
import com.panfan.hivelineage.v2.syntax.SqlSyntaxParser;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class LexicalModelContractTest {
    @Test
    public void originalLexemeAndDecodedValueRemainDistinct() {
        LexToken identifier = new LexToken(0, TokenKind.IDENTIFIER, "`Order`", "Order", TokenChannel.DEFAULT,
                new TokenLocation(SourceRef.synthetic("quoted-name"), new DialectSymbol("hive", "IDENTIFIER")));

        Assert.assertEquals("`Order`", identifier.getLexeme());
        Assert.assertEquals("Order", identifier.getDecodedValue());
        Assert.assertEquals("IDENTIFIER", identifier.getLocation().getDialectSymbol().getSymbolName());
    }

    @Test
    public void tokenBufferAndTriviaDoNotShareMutableLists() {
        LexToken token = new LexToken(0, TokenKind.STRING_LITERAL, "'text'", "text", TokenChannel.DEFAULT,
                new TokenLocation(SourceRef.synthetic("string"), new DialectSymbol("hive", "STRING_LITERAL")));
        List<LexToken> tokens = new ArrayList<LexToken>();
        tokens.add(token);
        List<Trivia> trivia = new ArrayList<Trivia>();
        trivia.add(new Trivia(TriviaKind.LINE_COMMENT, "-- unchanged", SourceRef.synthetic("comment")));
        LexedUnit lexed = new LexedUnit(expandedUnit(), tokens, trivia);
        tokens.clear();
        trivia.clear();

        Assert.assertEquals(1, lexed.getTokens().size());
        Assert.assertEquals("-- unchanged", lexed.getTrivia().get(0).getText());
        try {
            lexed.getTokens().clear();
            Assert.fail("tokens must be immutable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertSame(token, lexed.getTokens().get(0));
        }
    }

    @Test
    public void parserInputIsTheExplicitTokenArtifact() throws Exception {
        Assert.assertEquals(ParseResult.class,
                SqlSyntaxParser.class.getMethod("parse", LexedUnit.class, SyntaxOptions.class).getReturnType());
        Assert.assertEquals(LexResult.class,
                SqlLexer.class.getMethod("tokenize", ExpandedUnit.class, LexicalOptions.class).getReturnType());
        for (Field field : LexedUnit.class.getDeclaredFields()) {
            Assert.assertFalse("immutable buffers must not have parser cursor state", field.getName().toLowerCase().contains("cursor"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void successfulLexingRequiresAnArtifact() {
        new LexResult(null, Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void tokenIndexCannotBeNegative() {
        new LexToken(-1, TokenKind.UNKNOWN, "?", null, TokenChannel.DEFAULT,
                new TokenLocation(SourceRef.synthetic("unknown"), new DialectSymbol("hive", "UNKNOWN")));
    }

    private ExpandedUnit expandedUnit() {
        ScriptUnit scriptUnit = new ScriptUnit("script", 0, "'text'", ScriptUnitKind.SQL, SourceRef.synthetic("script"));
        SourceMap sourceMap = new SourceMap("script", "expanded", Collections.<SourceMapEntry>emptyList());
        return new ExpandedUnit(scriptUnit, "expanded", "'text'", sourceMap, Collections.<VariableEvidence>emptyList());
    }
}
