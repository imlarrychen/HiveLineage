package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class SemanticNames {
    private SemanticNames() {
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static List<String> parts(SyntaxName name) {
        List<String> result = new ArrayList<>();
        for (IdentifierPart part : name.getParts()) {
            result.add(normalize(part.getValue()));
        }
        return result;
    }

    static QualifiedName name(SyntaxName name) {
        return new QualifiedName(parts(name));
    }

    static String last(SyntaxName name) {
        List<String> parts = parts(name);
        return parts.get(parts.size() - 1);
    }

    static QualifiedName qualify(SyntaxName name, BindingContext context, boolean temporary) {
        List<String> parts = parts(name);
        if (temporary) {
            return new QualifiedName(Arrays.asList(temporaryDatabase(context), parts.get(parts.size() - 1)));
        }
        return qualify(parts, context.getSession().getCurrentDatabase());
    }

    static QualifiedName qualify(List<String> parts, Optional<QualifiedName> database) {
        if (parts.size() > 1) {
            return new QualifiedName(parts);
        }
        if (!database.isPresent()) {
            throw new BindingFailure("DATABASE_NOT_RESOLVED", "Unqualified object requires a current database");
        }
        List<String> qualified = new ArrayList<>(database(database.get()).getParts());
        qualified.addAll(parts);
        return new QualifiedName(qualified);
    }

    static QualifiedName database(QualifiedName database) {
        if (database.getParts().size() != 1) {
            throw new BindingFailure("INVALID_DATABASE_NAME", "Current database must be one identifier");
        }
        String value = database.getParts().get(0).trim();
        if (value.indexOf('.') >= 0) {
            throw new BindingFailure("INVALID_DATABASE_NAME", "Current database cannot contain a dot");
        }
        boolean startsQuoted = value.startsWith("`");
        boolean endsQuoted = value.endsWith("`");
        if (startsQuoted != endsQuoted || startsQuoted && value.length() < 2) {
            throw new BindingFailure("INVALID_DATABASE_NAME", "Current database has malformed quotes");
        }
        if (startsQuoted) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.indexOf('`') >= 0) {
            throw new BindingFailure("INVALID_DATABASE_NAME", "Current database has malformed quotes");
        }
        if (value.isEmpty()) {
            throw new BindingFailure("DATABASE_NOT_RESOLVED", "Unqualified object requires a nonempty database");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                throw new BindingFailure("INVALID_DATABASE_NAME", "Current database cannot contain whitespace");
            }
        }
        return new QualifiedName(Arrays.asList(normalize(value)));
    }

    static String temporaryDatabase(BindingContext context) {
        return "seele_tmp_db_" + normalizeIdPart(context.getSession().getInitialContext().getWorkflowId())
            + "_" + normalizeIdPart(context.getSession().getInitialContext().getTaskId());
    }

    static List<QualifiedName> temporaryLookupNames(SyntaxName original, BindingContext context) {
        Set<QualifiedName> names = new LinkedHashSet<>();
        names.add(qualify(original, context, true));
        names.add(name(original));
        names.add(new QualifiedName(Arrays.asList(last(original))));
        if (original.getParts().size() == 1 && context.getSession().getCurrentDatabase().isPresent()) {
            try {
                names.add(qualify(Arrays.asList(last(original)), context.getSession().getCurrentDatabase()));
            } catch (BindingFailure invalidDatabase) {
                // A temporary object's synthetic namespace does not require a physical default database.
            }
        }
        return new ArrayList<>(names);
    }

    private static String normalizeIdPart(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        String normalized = normalize(value.trim().replace("`", ""));
        StringBuilder result = new StringBuilder();
        boolean separator = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_') {
                result.append(character);
                separator = false;
            } else if (!separator) {
                result.append('_');
                separator = true;
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '_') {
            result.deleteCharAt(result.length() - 1);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }
}
