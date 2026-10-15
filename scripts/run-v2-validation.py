#!/usr/bin/env python3
"""Run repository and semantic engine assertions without modifying V1 sources.

The all suite runs the original generated-corpus bytecode in a temporary working
directory containing its 18 baseline SQL files and snapshot. JDBC checks inherit
the original fixture in a temporary subclass with an in-memory connection rule;
they exercise the real Provider and assertions, not a live MySQL server.
The legacy-oracle suite adapts temporary source copies to the semantic engine,
preserving assertions and adding the same JDBC rule only to its JDBC copy.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


VALIDATION_RUNNER = "com.panfan.hivelineage.v2.testing.ValidationRunner"
JDBC_FIXTURE = "com.panfan.hivelineage.JdbcMetadataColumnV2ProviderIntegrationTest"
STUBBED_JDBC_FIXTURE = "com.panfan.hivelineage.v2.testing.runnerfixtures.JdbcMetadataFixture"
GENERATED_CORPUS_TEST = "com.panfan.hivelineage.GeneratedSqlTestSetTest"
JDBC_RULE = ("\n    @org.junit.Rule\n"
            "    public final com.panfan.hivelineage.v2.metadata.JdbcMetadataConnectionRule jdbcConnection =\n"
            "        new com.panfan.hivelineage.v2.metadata.JdbcMetadataConnectionRule();\n")


def run(command, root):
    result = subprocess.run(command, cwd=root)
    if result.returncode:
        raise SystemExit(result.returncode)


def balanced_end(text, start, opening, closing, label):
    depth, quote, line_comment, block_comment = 1, None, False, False
    index = start + 1
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if line_comment:
            line_comment = char not in "\r\n"
        elif block_comment:
            if char == "*" and following == "/":
                block_comment = False
                index += 1
        elif quote:
            if char == "\\":
                index += 1
            elif char == quote:
                quote = None
        elif char == "/" and following in ("/", "*"):
            line_comment, block_comment = following == "/", following == "*"
            index += 1
        elif char in ("'", '"'):
            quote = char
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    raise RuntimeError("Unclosed Java " + label)


def method_end(text, start):
    return balanced_end(text, start, "{", "}", "test method")


def constructors_with_arguments(text):
    calls = []
    code = java_code_mask(text)
    for match in re.finditer(r"\bnew\s+HiveScriptLineageAnalyzer\s*\(", code):
        opening = match.end() - 1
        end = balanced_end(text, opening, "(", ")", "analyzer constructor")
        argument = text[opening + 1:end - 1].strip()
        if argument:
            calls.append((match.start(), end, argument))
    return calls


def java_code_mask(text):
    code = list(text)
    quote, line_comment, block_comment = None, False, False
    index = 0
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if line_comment:
            if char in "\r\n":
                line_comment = False
            else:
                code[index] = " "
        elif block_comment:
            code[index] = " "
            if char == "*" and following == "/":
                code[index + 1] = " "
                block_comment = False
                index += 1
        elif quote:
            code[index] = " "
            if char == "\\" and following:
                code[index + 1] = " "
                index += 1
            elif char == quote:
                quote = None
        elif char == "/" and following in ("/", "*"):
            code[index] = code[index + 1] = " "
            line_comment, block_comment = following == "/", following == "*"
            index += 1
        elif char in ("'", '"'):
            quote = char
            code[index] = " "
        index += 1
    return "".join(code)


def isolate_constructor_specific_tests(text, filename):
    matches = list(re.finditer(r"@Test\s+public void (\w+)\([^)]*\)\s*(?:throws [^{]+)?\{", text))
    for match in reversed(matches):
        start = match.end() - 1
        end = method_end(text, start)
        body = text[start:end]
        calls = constructors_with_arguments(body)
        if not calls:
            continue
        known = filename == "HiveViewAndTemporaryLineageTest.java" and match.group(1) == "viewExpansionFailureEmitsWarningAndKeepsViewBoundary"
        expected = (r'new\s+HiveAstParserAdapter\s*\(\s*\)\s*\{\s*@Override\s+'
                    r'public\s+ParsedHiveStatement\s+parse\s*\(\s*String\s+normalizedSql\s*\)\s*\{\s*'
                    r'if\s*\(\s*normalizedSql\.indexOf\("bad_view_marker"\)\s*>=\s*0\s*\)\s*\{\s*'
                    r'throw\s+new\s+IllegalArgumentException\("broken view definition"\);\s*\}\s*'
                    r'return\s+delegate\.parse\(normalizedSql\);\s*\}\s*\}')
        if not known or len(calls) != 1 or re.fullmatch(expected, calls[0][2]) is None:
            raise RuntimeError("Unsupported legacy oracle constructor fixture: " + filename + "#" + match.group(1))
        call_start, call_end, _ = calls[0]
        replacement = "com.panfan.hivelineage.v2.testing.ViewFrontendFaultAnalyzerFactory.createWithViewFrontendFault()"
        body = body[:call_start] + replacement + body[call_end:]
        text = text[:start] + body + text[end:]
        print("ADAPTED V1 view frontend fault fixture (original assertions preserved):", filename, match.group(1), flush=True)
    return text


def discover_test_sources(test_root, suite):
    sources = sorted(p for p in test_root.rglob("*Test.java") if "@Test" in p.read_text())
    if suite == "v2":
        package_root = test_root / "com/panfan/hivelineage/v2"
        sources = [p for p in sources if package_root in p.parents]
    return sources


def add_jdbc_rule(text, class_name):
    if "JdbcMetadataConnectionRule" in java_code_mask(text):
        raise RuntimeError("JDBC oracle fixture already declares a connection rule")
    declaration = re.search(r"\bpublic\s+class\s+" + re.escape(class_name) + r"\s*\{", java_code_mask(text))
    if declaration is None:
        raise RuntimeError("JDBC oracle fixture declaration is not supported: " + class_name)
    position = declaration.end()
    return text[:position] + JDBC_RULE + text[position:]


def compile_fixtures(javac, major, classpath, sources, output, root):
    output.mkdir()
    compiler_args = ["--release", "8"] if major >= 9 else ["-source", "1.8", "-target", "1.8"]
    run([javac] + compiler_args + ["-cp", classpath, "-d", str(output)] + [str(source) for source in sources], root)


def prepare_legacy_corpus(root, directory):
    relative = Path("src/test/resources/hql/generated")
    baseline = ["core_lineage_cases.sql"] + ["lineage_case_%02d.sql" % index for index in range(1, 18)]
    resources = baseline + ["lineage_parse_results.md"]
    missing = [name for name in resources if not (root / relative / name).is_file()]
    if missing:
        raise RuntimeError("Missing baseline corpus resources: " + ", ".join(missing))
    destination = directory / relative
    destination.mkdir(parents=True)
    for name in resources:
        shutil.copyfile(root / relative / name, destination / name)
    return directory


def run_repository_suite(java, java_args, javac, major, classpath, sources, test_root, root):
    names = [".".join(source.relative_to(test_root).with_suffix("").parts) for source in sources]
    # Existing V1 test bytecode remains unchanged. Its filesystem scan receives
    # only its original corpus, while every other test keeps the repository cwd.
    with tempfile.TemporaryDirectory(prefix="hive-validation-fixtures-") as temporary:
        directory = Path(temporary)
        original_jdbc = JDBC_FIXTURE in names
        original_corpus = GENERATED_CORPUS_TEST in names
        names = [name for name in names if name not in (JDBC_FIXTURE, GENERATED_CORPUS_TEST)]
        if names:
            run([java] + java_args + ["-cp", classpath, VALIDATION_RUNNER] + names, root)
        if original_jdbc:
            template = root / "src/test/resources/v2/runner-fixtures/JdbcMetadataFixture.java.txt"
            source = directory / "JdbcMetadataFixture.java"
            shutil.copyfile(template, source)
            output = directory / "classes"
            compile_fixtures(javac, major, classpath, [source], output, root)
            # The temporary subclass must be visible only to its own process,
            # never to the main suite's fixture-isolation assertions.
            jdbc_classpath = str(output) + os.pathsep + classpath
            run([java] + java_args + ["-cp", jdbc_classpath, VALIDATION_RUNNER, STUBBED_JDBC_FIXTURE], root)
        if original_corpus:
            isolated = prepare_legacy_corpus(root, directory / "legacy-corpus")
            print("ISOLATED V1 generated corpus: 18 original SQL files and unchanged snapshot", flush=True)
            run([java] + java_args + ["-cp", classpath, VALIDATION_RUNNER, GENERATED_CORPUS_TEST], isolated)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--suite", choices=("all", "v2", "legacy-oracle"), default="all",
                        help="all: original + semantic tests with isolated V1 fixtures; v2: semantic package only; legacy-oracle: temporary adapted V1 assertions")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--no-compile", action="store_true", help="Use existing target classes")
    parser.add_argument("--classpath-file", type=Path, help="Existing Maven test classpath or full runtime classpath")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
    maven = ["mvn"] + (["-o"] if args.offline else []) + ["-q"]
    if not args.no_compile:
        run(maven + ["-DskipTests", "test-compile"], root)
    classpath_file = args.classpath_file or root / "target/v2-validation-classpath.txt"
    if not args.classpath_file:
        run(maven + ["org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath",
                     "-DincludeScope=test", "-Dmdep.outputFile=" + str(classpath_file)], root)
    dependencies = classpath_file.resolve().read_text().strip().split(os.pathsep)
    classpath = os.pathsep.join([str(root / "target/classes"), str(root / "target/test-classes")]
                               + [str(Path(entry) if Path(entry).is_absolute() else root / entry) for entry in dependencies if entry])
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
    major = int(re.search(r'version "(\d+)', version.stderr + version.stdout).group(1))
    java_args = ["--add-opens=java.base/java.net=ALL-UNNAMED"] if major >= 9 else []
    test_root = root / "src/test/java"
    sources = discover_test_sources(test_root, args.suite)
    if args.suite != "legacy-oracle":
        run_repository_suite(java, java_args, javac, major, classpath, sources, test_root, root)
        return
    with tempfile.TemporaryDirectory(prefix="hive-legacy-oracle-") as temporary:
        directory = Path(temporary)
        names = []
        copied = []
        for source in sources:
            if source.parent != test_root / "com/panfan/hivelineage" or not source.name.startswith(("Hive", "JdbcMetadata")):
                continue
            text = source.read_text()
            if "new HiveScriptLineageAnalyzer(" not in text:
                continue
            text = isolate_constructor_specific_tests(text, source.name)
            if constructors_with_arguments(text):
                raise RuntimeError("Unsupported legacy oracle constructor fixture outside a test method: " + source.name)
            name = "LegacyOracle" + source.stem
            text = text.replace("com.panfan.hivelineage.core.HiveScriptLineageAnalyzer",
                                "com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter")
            text = re.sub(r"\bHiveScriptLineageAnalyzer\b", "HiveScriptLineageAdapter", text)
            text = re.sub(r"\b" + re.escape(source.stem) + r"\b", name, text)
            if source.stem == "JdbcMetadataColumnV2ProviderIntegrationTest":
                text = add_jdbc_rule(text, name)
            target = directory / (name + ".java")
            target.write_text(text)
            copied.append(str(target))
            names.append("com.panfan.hivelineage." + name)
        if not names:
            raise RuntimeError("No V1 analyzer regression tests were discovered")
        output = directory / "classes"
        compile_fixtures(javac, major, classpath, copied, output, root)
        print("Original assertion classes:", len(names), flush=True)
        run([java] + java_args + ["-cp", str(output) + os.pathsep + classpath,
                                VALIDATION_RUNNER] + names, root)


if __name__ == "__main__":
    main()
