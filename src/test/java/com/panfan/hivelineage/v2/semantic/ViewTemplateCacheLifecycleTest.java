package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ViewTemplateCacheLifecycleTest {
    @Test
    public void expiryAndLruEvictionBoundTemplatesWithoutChangingReturnedImmutableValue() {
        AtomicLong clock = new AtomicLong(100L);
        DefaultViewTemplateCache cache = new DefaultViewTemplateCache(2, 10L, clock::get);
        SyntaxTemplate first = template("first");
        cache.put(key("first"), first);
        cache.put(key("second"), template("second"));
        assertSame(first, cache.get(key("first")).get());
        cache.put(key("third"), template("third"));
        assertFalse(cache.get(key("second")).isPresent());
        assertTrue(cache.get(key("third")).isPresent());
        clock.set(110L);
        assertFalse(cache.get(key("first")).isPresent());
        assertTrue(first.getStatement() instanceof OtherStatementSyntax);
        SyntaxTemplate refreshed = template("refreshed");
        cache.put(key("first"), refreshed);
        assertSame(refreshed, cache.get(key("first")).get());
    }

    private static ViewTemplateKey key(String name) {
        return new ViewTemplateKey(new DefinitionId(name), "digest", "hive", "context", new MetadataReadContext(
                "source", "cluster", Optional.empty(), Collections.emptyMap()));
    }

    private static SyntaxTemplate template(String name) {
        SourceRef source = SourceRef.synthetic(name);
        return new SyntaxTemplate(new NodeId(name), new OtherStatementSyntax(new NodeId(name + ":statement"), source, "DDL", null), source);
    }
}
