"""Reject absent/empty/skipped core target reports instead of counting task success."""

from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
targets = sys.argv[1:]
assert targets and set(targets) <= {
    "jvmTest", "testAndroidHostTest", "wasmJsBrowserTest", "iosSimulatorArm64Test"
}
results = {}
for target in targets:
    files = list((root / "core/build/test-results" / target).glob("TEST-*.xml"))
    assert files, f"No actual reports for {target}"
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, "0"))
    assert counts["tests"] == 14, (target, counts)
    assert counts["failures"] == counts["errors"] == counts["skipped"] == 0, (target, counts)
    results[target] = counts
print(json.dumps(results))
