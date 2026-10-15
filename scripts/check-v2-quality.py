#!/usr/bin/env python3
"""Run the JDK AST complexity gate offline (Java 8+); baseline updates are explicit."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_bin = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path("")
with tempfile.TemporaryDirectory(prefix="v2-quality-") as output:
    subprocess.run([str(java_bin / "javac") if str(java_bin) != "." else "javac",
                    "-d", output, str(root / "scripts/quality/V2ComplexityGate.java")], check=True)
    subprocess.run([str(java_bin / "java") if str(java_bin) != "." else "java", "-cp", output,
                    "V2ComplexityGate", str(root / "src/main/java/com/panfan/hivelineage/v2"),
                    str(root / "scripts/quality/v2-complexity-baseline.tsv")], check=True)
