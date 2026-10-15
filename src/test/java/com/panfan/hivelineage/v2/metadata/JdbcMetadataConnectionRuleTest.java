package com.panfan.hivelineage.v2.metadata;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JdbcMetadataConnectionRuleTest {
    @Test
    public void ordinaryJUnitRuleRestoresPropertiesAndUnregistersItsDriverAfterSuccess() throws Throwable {
        String[] previous = properties();
        String[] url = new String[1];
        Statement body = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                url[0] = System.getProperty("lineage.mysql.url");
                assertTrue(url[0].startsWith("jdbc:lineage-stub:"));
                try (Connection connection = DriverManager.getConnection(url[0])) {
                    assertTrue(!connection.isClosed());
                }
            }
        };
        new JdbcMetadataConnectionRule().apply(body, description()).evaluate();
        assertPropertiesRestored(previous);
        assertThrows(SQLException.class, () -> DriverManager.getDriver(url[0]));
    }

    @Test
    public void failingTestPreservesItsCauseWhileRestoringPropertiesAndUnregisteringDriver() {
        String[] previous = properties();
        String[] url = new String[1];
        IllegalStateException failure = new IllegalStateException("intentional rule fixture failure");
        Statement body = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                url[0] = System.getProperty("lineage.mysql.url");
                try (Connection connection = DriverManager.getConnection(url[0])) {
                    throw failure;
                }
            }
        };
        Statement scoped = new JdbcMetadataConnectionRule().apply(body, description());
        assertSame(failure, assertThrows(IllegalStateException.class, scoped::evaluate));
        assertPropertiesRestored(previous);
        assertThrows(SQLException.class, () -> DriverManager.getDriver(url[0]));
    }

    @Test
    public void leakedConnectionIsARealFailureAndStillCannotLeaveItsDriverOrPropertiesInstalled() throws Exception {
        String[] previous = properties();
        String[] url = new String[1];
        Connection[] leaked = new Connection[1];
        Statement body = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                url[0] = System.getProperty("lineage.mysql.url");
                leaked[0] = DriverManager.getConnection(url[0]);
            }
        };
        Statement scoped = new JdbcMetadataConnectionRule().apply(body, description());
        try {
            assertThrows(AssertionError.class, scoped::evaluate);
            assertPropertiesRestored(previous);
            assertThrows(SQLException.class, () -> DriverManager.getDriver(url[0]));
        } finally {
            if (leaked[0] != null) {
                leaked[0].close();
            }
        }
    }

    private static Description description() {
        return Description.createTestDescription(JdbcMetadataConnectionRuleTest.class, "scoped fixture");
    }

    private static String[] properties() {
        return new String[] { System.getProperty("lineage.mysql.url"), System.getProperty("lineage.mysql.user"), System.getProperty("lineage.mysql.password") };
    }

    private static void assertPropertiesRestored(String[] previous) {
        String[] after = properties();
        for (int index = 0; index < previous.length; index++) {
            assertTrue("Prior JDBC property must be restored without exposing its value", Objects.equals(previous[index], after[index]));
        }
    }
}
