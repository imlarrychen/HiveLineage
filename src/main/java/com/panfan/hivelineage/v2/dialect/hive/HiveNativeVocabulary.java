package com.panfan.hivelineage.v2.dialect.hive;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

/** Adapts Hive's token vocabulary, including virtual AST tokens, without interpreting user text. */
final class HiveNativeVocabulary {
    private static final Map<Integer, String> NATIVE_SYMBOLS = nativeSymbols();

    String kind(ASTNode node) {
        if (node == null || node.getType() < 0) {
            return "";
        }
        String symbol = NATIVE_SYMBOLS.get(node.getType());
        return symbol == null ? node.getText() : symbol;
    }

    private static Map<Integer, String> nativeSymbols() {
        Map<Integer, String> symbols = new HashMap<Integer, String>();
        for (int index = 0; index < HiveParser.tokenNames.length; index++) {
            symbols.put(index, HiveParser.tokenNames[index]);
        }
        // Hive's public vocabulary also includes virtual AST types beyond tokenNames.
        for (Field field : HiveParser.class.getDeclaredFields()) {
            if (field.getType() == int.class && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
                try {
                    symbols.put(field.getInt(null), field.getName());
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("Cannot read Hive token vocabulary", error);
                }
            }
        }
        return Collections.unmodifiableMap(symbols);
    }

}
