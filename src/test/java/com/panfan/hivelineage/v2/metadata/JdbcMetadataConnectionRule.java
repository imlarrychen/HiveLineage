package com.panfan.hivelineage.v2.metadata;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Installs the test JDBC boundary for every method, regardless of the JUnit entry point. */
public final class JdbcMetadataConnectionRule implements TestRule {
    private static final Object PROPERTY_EVALUATION_LOCK = new Object();

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                synchronized (PROPERTY_EVALUATION_LOCK) {
                    try (JdbcMetadataConnectionStub stub = JdbcMetadataConnectionStub.open();
                            JdbcMetadataConnectionStub.PropertyScope properties = stub.installLegacyProperties()) {
                        System.out.println("JDBC mode: in-memory connection stub (JUnit rule; original Provider and assertions)");
                        base.evaluate();
                    }
                }
            }
        };
    }
}
