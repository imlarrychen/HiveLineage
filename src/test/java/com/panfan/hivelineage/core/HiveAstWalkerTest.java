package com.panfan.hivelineage.core;

import java.util.List;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.junit.Assert;
import org.junit.Test;

public class HiveAstWalkerTest {
    @Test
    public void walksHiveAstByTokenAndDirectChildren() throws Exception {
        ASTNode root = new ParseDriver().parse(
            "insert overwrite table dwd.user_tags "
                + "select user_id, tag "
                + "from ods.user_base lateral view explode(tags) exploded_tags as tag"
        ).getTree();

        HiveAstWalker walker = new HiveAstWalker();

        ASTNode query = walker.first(root, HiveParser.TOK_QUERY);
        ASTNode insert = walker.directChild(query, HiveParser.TOK_INSERT);
        List<ASTNode> tableRefs = walker.collect(root, HiveParser.TOK_TABREF);

        Assert.assertNotNull(query);
        Assert.assertNotNull(insert);
        Assert.assertEquals(1, tableRefs.size());
        Assert.assertNotNull(walker.first(root, HiveParser.TOK_LATERAL_VIEW));
    }
}
