package com.panfan.hivelineage.v2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class V2ValidationRunnerTest {
    @Test
    public void jdbcFixtureRunsOriginalAssertionsWithAnAutomaticallyInstalledConnectionStub() throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(V2ValidationRunner.class.getName());
        command.add("com.panfan.hivelineage.JdbcMetadataColumnV2ProviderIntegrationTest");
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
            assertTrue(report, report.contains("JDBC mode: in-memory connection stub"));
            assertTrue(report, report.contains("Tests=5, passed=5, failures=0, assumptionSkips=0"));
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void methodAssumptionFailureAndIgnoredTestHaveSeparateCounts() {
        V2ValidationRunner.Statistics statistics = V2ValidationRunner.run(MixedFixture.class);
        assertEquals(3, statistics.getResult().getRunCount());
        assertEquals(1, statistics.getPassed());
        assertEquals(1, statistics.getAssumptionSkips());
        assertEquals(0, statistics.getClassAssumptionSkips());
        assertEquals(0, statistics.getClassFailures());
        assertEquals(1, statistics.getResult().getFailureCount());
        assertEquals(1, statistics.getResult().getIgnoreCount());
        assertFalse(statistics.getResult().wasSuccessful());
    }

    @Test
    public void beforeClassAssumptionDoesNotCountAsAnExecutedOrPassingTest() {
        V2ValidationRunner.Statistics statistics = V2ValidationRunner.run(UnavailableFixture.class);
        assertEquals(0, statistics.getResult().getRunCount());
        assertEquals(0, statistics.getPassed());
        assertEquals(0, statistics.getAssumptionSkips());
        assertEquals(1, statistics.getClassAssumptionSkips());
        assertEquals(0, statistics.getClassFailures());
        assertTrue(statistics.getResult().wasSuccessful());
        assertTrue(statistics.summary().contains("passed=0"));
    }

    @Test
    public void beforeClassFailureCannotCreateNegativePassedCount() {
        V2ValidationRunner.Statistics statistics = V2ValidationRunner.run(BrokenFixture.class);
        assertEquals(0, statistics.getResult().getRunCount());
        assertEquals(0, statistics.getPassed());
        assertEquals(1, statistics.getResult().getFailureCount());
        assertEquals(1, statistics.getClassFailures());
        assertFalse(statistics.getResult().wasSuccessful());
    }

    public static class MixedFixture {
        @Test
        public void passes() {
            assertTrue(true);
        }

        @Test
        public void skips() {
            Assume.assumeTrue("method prerequisite unavailable", false);
        }

        @Test
        public void fails() {
            fail("intentional runner fixture");
        }

        @Ignore("intentional ignored runner fixture")
        @Test
        public void ignored() {
            fail("ignored fixture must not execute");
        }
    }

    public static class UnavailableFixture {
        @BeforeClass
        public static void prerequisite() {
            Assume.assumeTrue("class prerequisite unavailable", false);
        }

        @Test
        public void notStarted() {
            fail("class prerequisite must prevent execution");
        }
    }

    public static class BrokenFixture {
        @BeforeClass
        public static void prerequisite() {
            fail("intentional class fixture failure");
        }

        @Test
        public void notStarted() {
            fail("class failure must prevent execution");
        }
    }
}
