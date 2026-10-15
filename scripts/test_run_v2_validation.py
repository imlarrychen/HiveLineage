"""Regression checks for semantic constructor adaptation without removing assertions."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("v2_validation", ROOT / "scripts/run-v2-validation.py")
VALIDATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATION)


class ConstructorAdaptationTest(unittest.TestCase):
    def test_known_fault_preserves_every_original_character_outside_the_constructor(self):
        path = ROOT / "src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java"
        original = path.read_text()
        start, end, _ = VALIDATION.constructors_with_arguments(original)[0]
        replacement = "com.panfan.hivelineage.v2.testing.ViewFrontendFaultAnalyzerFactory.createWithViewFrontendFault()"
        with contextlib.redirect_stdout(io.StringIO()) as output:
            adapted = VALIDATION.isolate_constructor_specific_tests(original, path.name)
        self.assertEqual(original[:start] + replacement + original[end:], adapted)
        self.assertIn('assertContainsSource(statement, "dim.v_broken_user");', adapted)
        self.assertIn("assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_EXPANSION_FAILED);", adapted)
        self.assertNotIn("assumeTrue", adapted)
        self.assertIn("ADAPTED", output.getvalue())
        self.assertEqual(original, path.read_text())

    def test_unknown_constructor_fixture_fails_without_an_assumption(self):
        source = "@Test public void otherFault() { new HiveScriptLineageAnalyzer(otherParser); assertEquals(1, result); }"
        with self.assertRaisesRegex(RuntimeError, "Unsupported legacy oracle constructor fixture: OtherTest.java#otherFault"):
            VALIDATION.isolate_constructor_specific_tests(source, "OtherTest.java")

    def test_changed_known_fault_is_not_silently_adapted(self):
        path = ROOT / "src/test/java/com/panfan/hivelineage/HiveViewAndTemporaryLineageTest.java"
        source = path.read_text().replace('"broken view definition"', '"different failure"')
        with self.assertRaisesRegex(RuntimeError, "Unsupported legacy oracle constructor fixture"):
            VALIDATION.isolate_constructor_specific_tests(source, path.name)

    def test_empty_constructors_and_inert_text_do_not_require_adaptation(self):
        source = ('@Test public void ordinary() { new HiveScriptLineageAnalyzer( ); '
                  'String note = "new HiveScriptLineageAnalyzer(fake)"; '
                  '/* new HiveScriptLineageAnalyzer(fake) */ }')
        self.assertEqual([], VALIDATION.constructors_with_arguments(source))
        self.assertEqual(source, VALIDATION.isolate_constructor_specific_tests(source, "OrdinaryTest.java"))


class TestSourceDiscoveryTest(unittest.TestCase):
    def test_v2_suite_uses_the_package_path_instead_of_test_class_names(self):
        paths = (
            "com/panfan/hivelineage/v2/api/StatementLineageIntegrationTest.java",
            "com/panfan/hivelineage/v2/compat/GeneratedSqlCompatibilityRegressionTest.java",
            "com/panfan/hivelineage/GeneratedV2NameOnlyTest.java",
            "other/v2/UnrelatedTest.java",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in paths:
                source = root / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("@Test public void runs() {}")
            self.assertEqual([root / relative for relative in paths[:2]],
                             VALIDATION.discover_test_sources(root, "v2"))
            self.assertEqual(sorted(root / relative for relative in paths),
                             VALIDATION.discover_test_sources(root, "all"))


class LegacyOracleGenerationTest(unittest.TestCase):
    def test_renamed_adapter_and_oracle_keep_access_to_root_package_helpers(self):
        original = ("package com.panfan.hivelineage;\n"
                    "import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;\n"
                    "public class HiveOriginalTest {\n"
                    "@Test public void copies() { new HiveScriptLineageAnalyzer(); TestSupport.assertTrue(true, \"ok\"); }\n"
                    "}\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "src/test/java/com/panfan/hivelineage/HiveOriginalTest.java"
            source.parent.mkdir(parents=True)
            source.write_text(original)
            classpath = root / "classpath.txt"
            classpath.write_text("fixture-classpath")
            commands = []
            copied = []

            def capture(command, directory):
                commands.append(command)
                if any(argument.endswith(".java") for argument in command):
                    copied.extend(Path(argument).read_text() for argument in command if argument.endswith(".java"))

            arguments = ["run-v2-validation.py", "--suite", "legacy-oracle", "--no-compile",
                         "--classpath-file", str(classpath)]
            with mock.patch.object(VALIDATION, "__file__", str(root / "scripts/run-v2-validation.py")), \
                    mock.patch("sys.argv", arguments), \
                    mock.patch.object(VALIDATION.subprocess, "run", return_value=SimpleNamespace(stderr='openjdk version "17.0.1"', stdout="")), \
                    mock.patch.object(VALIDATION, "run", side_effect=capture), \
                    contextlib.redirect_stdout(io.StringIO()):
                VALIDATION.main()
            self.assertEqual(1, len(copied))
            self.assertIn("package com.panfan.hivelineage;", copied[0])
            self.assertIn("public class LegacyOracleHiveOriginalTest", copied[0])
            self.assertIn("com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter", copied[0])
            self.assertIn("new HiveScriptLineageAdapter()", copied[0])
            self.assertIn("TestSupport.assertTrue", copied[0])
            self.assertIn("com.panfan.hivelineage.v2.testing.ValidationRunner", commands[-1])
            self.assertEqual("com.panfan.hivelineage.LegacyOracleHiveOriginalTest", commands[-1][-1])
            self.assertEqual(original, source.read_text())


class JdbcOracleRuleInjectionTest(unittest.TestCase):
    def test_rule_insertion_preserves_every_original_fixture_character(self):
        path = ROOT / "src/test/java/com/panfan/hivelineage/JdbcMetadataColumnV2ProviderIntegrationTest.java"
        original = path.read_text()
        adapted = VALIDATION.add_jdbc_rule(original, path.stem)
        self.assertEqual(original, adapted.replace(VALIDATION.JDBC_RULE, "", 1))
        self.assertEqual(5, adapted.count("@Test"))
        self.assertEqual(original.count("Assume.assumeTrue"), adapted.count("Assume.assumeTrue"))
        self.assertEqual(original, path.read_text())

    def test_existing_rule_is_rejected_instead_of_being_added_twice(self):
        source = "public class Example { JdbcMetadataConnectionRule rule; }"
        with self.assertRaisesRegex(RuntimeError, "already declares"):
            VALIDATION.add_jdbc_rule(source, "Example")


class LegacyCorpusIsolationTest(unittest.TestCase):
    def test_only_original_sql_and_snapshot_are_copied_without_changing_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repository"
            source = root / "src/test/resources/hql/generated"
            source.mkdir(parents=True)
            baseline = ["core_lineage_cases.sql"] + ["lineage_case_%02d.sql" % index for index in range(1, 18)]
            files = baseline + ["lineage_parse_results.md", "v2_extra.sql", "lineage_case_metadata.md"]
            originals = {name: (name + "\r\n原样\n").encode() for name in files}
            for name, content in originals.items():
                (source / name).write_bytes(content)
            isolated = VALIDATION.prepare_legacy_corpus(root, Path(temporary) / "isolated")
            destination = isolated / "src/test/resources/hql/generated"
            self.assertEqual(set(baseline + ["lineage_parse_results.md"]), {p.name for p in destination.iterdir()})
            for name in baseline + ["lineage_parse_results.md"]:
                self.assertEqual(originals[name], (destination / name).read_bytes())
            self.assertEqual(originals, {p.name: p.read_bytes() for p in source.iterdir()})

    def test_missing_original_resource_fails_before_starting_a_runner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(RuntimeError, "Missing baseline corpus resources"):
                VALIDATION.prepare_legacy_corpus(root, root / "isolated")
            self.assertFalse((root / "isolated").exists())


class RepositorySuiteIsolationTest(unittest.TestCase):
    def test_jdbc_subclass_and_original_generated_bytecode_run_in_their_intended_contexts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            test_root = root / "src/test/java"
            source_names = ("com/panfan/hivelineage/JdbcMetadataColumnV2ProviderIntegrationTest.java",
                            "com/panfan/hivelineage/GeneratedSqlTestSetTest.java",
                            "com/panfan/hivelineage/v2/api/ExampleTest.java")
            sources = [test_root / name for name in source_names]
            template = root / "src/test/resources/v2/runner-fixtures/JdbcMetadataFixture.java.txt"
            template.parent.mkdir(parents=True)
            template.write_bytes((ROOT / "src/test/resources/v2/runner-fixtures/JdbcMetadataFixture.java.txt").read_bytes())
            corpus = root / "src/test/resources/hql/generated"
            corpus.mkdir(parents=True)
            baseline = ["core_lineage_cases.sql"] + ["lineage_case_%02d.sql" % index for index in range(1, 18)]
            for name in baseline + ["lineage_parse_results.md", "v2_extra.sql"]:
                (corpus / name).write_text(name)
            commands = []
            isolated_directories = []

            def capture(command, directory):
                commands.append((command, directory))
                if command[0] == "javac":
                    copied = Path(command[-1]).read_text()
                    self.assertIn("package com.panfan.hivelineage.v2.testing.runnerfixtures;", copied)
                    self.assertIn("extends com.panfan.hivelineage.JdbcMetadataColumnV2ProviderIntegrationTest", copied)
                    self.assertNotIn("@Test", copied)
                elif command[-1] == VALIDATION.GENERATED_CORPUS_TEST:
                    self.assertNotEqual(root, directory)
                    self.assertEqual(str(root / "classes"), command[command.index("-cp") + 1])
                    self.assertEqual(set(baseline + ["lineage_parse_results.md"]),
                                     {p.name for p in (directory / "src/test/resources/hql/generated").iterdir()})
                    isolated_directories.append(directory)
                elif command[-1] == VALIDATION.STUBBED_JDBC_FIXTURE:
                    self.assertEqual(root, directory)
                    classpath = command[command.index("-cp") + 1]
                    self.assertTrue(classpath.endswith(VALIDATION.os.pathsep + str(root / "classes")))
                    self.assertNotEqual(str(root / "classes"), classpath)
                    self.assertEqual([VALIDATION.STUBBED_JDBC_FIXTURE], command[command.index(VALIDATION.VALIDATION_RUNNER) + 1:])
                else:
                    self.assertEqual(root, directory)
                    self.assertEqual(str(root / "classes"), command[command.index("-cp") + 1])
                    self.assertNotIn(VALIDATION.STUBBED_JDBC_FIXTURE, command)
                    self.assertNotIn(VALIDATION.JDBC_FIXTURE, command)
                    self.assertNotIn(VALIDATION.GENERATED_CORPUS_TEST, command)
                    self.assertIn("com.panfan.hivelineage.v2.api.ExampleTest", command)

            with mock.patch.object(VALIDATION, "run", side_effect=capture), contextlib.redirect_stdout(io.StringIO()):
                VALIDATION.run_repository_suite("java", [], "javac", 17, str(root / "classes"),
                                               sources, test_root, root)
            self.assertEqual(4, len(commands))
            self.assertEqual("com.panfan.hivelineage.v2.api.ExampleTest", commands[0][0][-1])
            self.assertEqual(VALIDATION.STUBBED_JDBC_FIXTURE, commands[2][0][-1])
            self.assertTrue(isolated_directories)
            self.assertTrue(all(not directory.exists() for directory in isolated_directories))
            self.assertTrue((corpus / "v2_extra.sql").is_file())


if __name__ == "__main__":
    unittest.main()
