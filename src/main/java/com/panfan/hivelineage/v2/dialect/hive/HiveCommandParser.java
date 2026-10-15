package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CommandSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.ResourceCommandKindSyntax;
import com.panfan.hivelineage.v2.syntax.ResourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SourceCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HiveCommandParser {
    SyntaxStatement parse(LexedUnit unit) {
        return parse(unit, new HiveTokenIndex(unit));
    }

    SyntaxStatement parse(LexedUnit unit, HiveTokenIndex index) {
        List<LexToken> tokens = index.visible();
        if (tokens.isEmpty()) {
            return null;
        }
        String first = tokens.get(0).getLexeme().toLowerCase(Locale.ROOT);
        if ("use".equals(first) && (tokens.size() != 2 || !databaseIdentifier(tokens.get(1)))) {
            return new OtherStatementSyntax(new NodeId(unit.getExpandedUnit().getExpandedSourceId() + ":invalid-use"),
                    unit.getExpandedUnit().getOriginalUnit().getSourceRef(), "USE", "INVALID_USE_DATABASE", false);
        }
        CommandSyntax command = command(first, tokens, unit.getExpandedUnit().getExpandedText());
        if (command == null) {
            return null;
        }
        SourceRef source = unit.getExpandedUnit().getOriginalUnit().getSourceRef();
        return new CommandStatementSyntax(new NodeId(unit.getExpandedUnit().getExpandedSourceId() + ":command"), source, command);
    }

    private boolean databaseIdentifier(LexToken token) {
        return new HiveIdentifierReader().read(token) != null;
    }

    private CommandSyntax command(String kind, List<LexToken> tokens, String text) {
        if ("set".equals(kind)) {
            return set(tokens, text);
        }
        if ("use".equals(kind) && tokens.size() == 2) {
            LexToken database = tokens.get(1);
            IdentifierPart part = new HiveIdentifierReader().read(database);
            return new UseCommandSyntax(new SyntaxName(java.util.Collections.singletonList(part)));
        }
        if ("source".equals(kind) && tokens.size() >= 2) {
            return new SourceCommandSyntax(HiveAstSupport.unquote(text.substring(tokens.get(1).getSourceRef().getSpan().getStart()).trim()));
        }
        if (("add".equals(kind) || "delete".equals(kind) || "list".equals(kind)) && resourceKind(tokens)) {
            List<String> resources = new ArrayList<String>();
            StringBuilder path = new StringBuilder();
            int end = -1;
            for (int index = 2; index < tokens.size(); index++) {
                LexToken token = tokens.get(index);
                if (path.length() > 0 && token.getSourceRef().getSpan().getStart() > end) {
                    resources.add(HiveAstSupport.unquote(path.toString()));
                    path.setLength(0);
                }
                path.append(token.getLexeme());
                end = token.getSourceRef().getSpan().getEnd();
            }
            if (path.length() > 0) {
                resources.add(HiveAstSupport.unquote(path.toString()));
            }
            String type = tokens.size() > 1 ? tokens.get(1).getLexeme() : "";
            return new ResourceCommandSyntax(ResourceCommandKindSyntax.valueOf(kind.toUpperCase(Locale.ROOT)), type, resources);
        }
        return null;
    }

    private boolean resourceKind(List<LexToken> tokens) {
        if (tokens.size() < 2) {
            return false;
        }
        String kind = tokens.get(1).getLexeme();
        return "jar".equalsIgnoreCase(kind) || "file".equalsIgnoreCase(kind) || "archive".equalsIgnoreCase(kind);
    }

    private SetCommandSyntax set(List<LexToken> tokens, String text) {
        StringBuilder key = new StringBuilder();
        int equals = -1;
        for (int index = 1; index < tokens.size(); index++) {
            if ("=".equals(tokens.get(index).getLexeme())) {
                equals = index;
                break;
            }
            key.append(tokens.get(index).getLexeme());
        }
        String namespace = "unqualified";
        String name = key.toString();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            namespace = name.substring(0, colon);
            name = name.substring(colon + 1);
        }
        String value = equals < 0 ? null : text.substring(tokens.get(equals).getSourceRef().getSpan().getEnd(),
                tokens.get(tokens.size() - 1).getSourceRef().getSpan().getEnd()).trim();
        return new SetCommandSyntax(namespace, name, value == null ? null : HiveAstSupport.unquote(value));
    }
}
