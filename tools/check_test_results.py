"""Reject absent/empty/skipped target reports instead of counting task success."""

from pathlib import Path
import json
import argparse
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--module", choices=["core", "ktor", "compose", "matrix"], default="core")
parser.add_argument("targets", nargs="+")
args = parser.parse_args()
targets = args.targets
expected_common = {"core": 28, "ktor": 17, "compose": 5, "matrix": 19}[args.module]
assert targets and set(targets) <= {
    "jvmTest", "testAndroidHostTest", "wasmJsBrowserTest", "iosSimulatorArm64Test"
}
results = {}
for target in targets:
    files = list((root / args.module / "build/test-results" / target).glob("TEST-*.xml"))
    assert files, f"No actual reports for {target}"
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, "0"))
    expected = expected_common + (6 if args.module == "compose" and target == "jvmTest" else 0)
    assert counts["tests"] == expected, (target, counts)
    assert counts["failures"] == counts["errors"] == counts["skipped"] == 0, (target, counts)
    results[target] = counts
print(json.dumps(results))
