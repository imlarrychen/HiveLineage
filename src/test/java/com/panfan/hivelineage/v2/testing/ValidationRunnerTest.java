package com.panfan.hivelineage.v2.testing;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ValidationRunnerTest {
    @Test
    public void deliberateFailureFixturesCannotBeDiscoveredFromTheProjectTestClasspath() throws Exception {
        ClassLoader application = ValidationRunnerTest.class.getClassLoader();
        for (String name : new String[]{"MixedFixture", "UnavailableFixture", "BrokenFixture"}) {
            assertNull(application.getResource("com/panfan/hivelineage/v2/testing/ValidationRunnerTest$" + name + ".class"));
        }
        assertNull(application.getResource("com/panfan/hivelineage/LegacyOracleJdbcMetadataColumnV2ProviderIntegrationTest.class"));
        assertNull(application.getResource("com/panfan/hivelineage/v2/testing/runnerfixtures/JdbcMetadataFixture.class"));
        try (RuntimeRunnerFixtures fixtures = RuntimeRunnerFixtures.open("MixedFixture")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(fixtures.getFixture().getName(), false, application));
            assertEquals(1, ValidationRunner.run(fixtures.getFixture()).getResult().getFailureCount());
        }
    }

    @Test
    public void temporaryJdbcSubclassRunsUnchangedOriginalAssertionsWithItsOwnRule() throws Exception {
        try (RuntimeRunnerFixtures fixtures = RuntimeRunnerFixtures.open("JdbcMetadataFixture")) {
            assertEquals("com.panfan.hivelineage.JdbcMetadataColumnV2ProviderIntegrationTest",
                    fixtures.getFixture().getSuperclass().getName());
            assertEquals(0, fixtures.getFixture().getDeclaredMethods().length);
            ArrayList<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            if (!System.getProperty("java.specification.version").startsWith("1.")) {
                command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
            }
            command.add("-cp");
            command.add(fixtures.getClassesDirectory() + File.pathSeparator + System.getProperty("java.class.path"));
            command.add(ValidationRunner.class.getName());
            command.add(fixtures.getFixture().getName());
            Path output = Files.createTempFile("v2-jdbc-runner-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = builder.start();
            try {
                assertTrue("JDBC fixture runner timed out", process.waitFor(30, TimeUnit.SECONDS));
                String report = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
                assertEquals(report, 0, process.exitValue());
                assertTrue(report, report.contains("JDBC mode: in-memory connection stub (JUnit rule;"));
                assertTrue(report, report.contains("Tests=5, passed=5, failures=0, assumptionSkips=0"));
            } finally {
                process.destroyForcibly();
                Files.deleteIfExists(output);
            }
        }
    }

    @Test
    public void methodAssumptionFailureAndIgnoredTestHaveSeparateCounts() throws Exception {
        try (RuntimeRunnerFixtures fixtures = RuntimeRunnerFixtures.open("MixedFixture")) {
            ValidationRunner.Statistics statistics = ValidationRunner.run(fixtures.getFixture());
            assertEquals(3, statistics.getResult().getRunCount());
            assertEquals(1, statistics.getPassed());
            assertEquals(1, statistics.getAssumptionSkips());
            assertEquals(0, statistics.getClassAssumptionSkips());
            assertEquals(0, statistics.getClassFailures());
            assertEquals(1, statistics.getResult().getFailureCount());
            assertEquals(1, statistics.getResult().getIgnoreCount());
            assertFalse(statistics.getResult().wasSuccessful());
        }
    }

    @Test
    public void beforeClassAssumptionDoesNotCountAsAnExecutedOrPassingTest() throws Exception {
        try (RuntimeRunnerFixtures fixtures = RuntimeRunnerFixtures.open("UnavailableFixture")) {
            ValidationRunner.Statistics statistics = ValidationRunner.run(fixtures.getFixture());
            assertEquals(0, statistics.getResult().getRunCount());
            assertEquals(0, statistics.getPassed());
            assertEquals(0, statistics.getAssumptionSkips());
            assertEquals(1, statistics.getClassAssumptionSkips());
            assertEquals(0, statistics.getClassFailures());
            assertTrue(statistics.getResult().wasSuccessful());
            assertTrue(statistics.summary().contains("passed=0"));
        }
    }

    @Test
    public void beforeClassFailureCannotCreateNegativePassedCount() throws Exception {
        try (RuntimeRunnerFixtures fixtures = RuntimeRunnerFixtures.open("BrokenFixture")) {
            ValidationRunner.Statistics statistics = ValidationRunner.run(fixtures.getFixture());
            assertEquals(0, statistics.getResult().getRunCount());
            assertEquals(0, statistics.getPassed());
            assertEquals(1, statistics.getResult().getFailureCount());
            assertEquals(1, statistics.getClassFailures());
            assertFalse(statistics.getResult().wasSuccessful());
        }
    }

}
