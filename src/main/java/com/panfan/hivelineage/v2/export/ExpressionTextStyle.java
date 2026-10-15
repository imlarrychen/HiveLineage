package com.panfan.hivelineage.v2.export;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Presentation-only keyword spelling; quoted strings and identifiers retain their bytes.
 */
final class ExpressionTextStyle {
    private static final Set<String> CASE_KEYWORDS = new HashSet<String>(Arrays.asList("CASE", "WHEN", "THEN", "ELSE", "END",
            "BETWEEN", "AND", "OR", "NOT", "IN", "LIKE", "RLIKE", "REGEXP", "IS", "NULL"));

    static String caseKeywords(String text) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        char quote = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (quote != 0) {
                result.append(current);
                if (current == '\\' && index + 1 < text.length()) {
                    result.append(text.charAt(++index));
                } else if (current == quote) {
                    quote = 0;
                }
                index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                result.append(current);
                index++;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int end = index + 1;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(index, end);
                result.append(CASE_KEYWORDS.contains(word.toUpperCase(Locale.ROOT)) ? word.toLowerCase(Locale.ROOT) : word);
                index = end;
            } else {
                result.append(current);
                index++;
            }
        }
        return result.toString();
    }
}
