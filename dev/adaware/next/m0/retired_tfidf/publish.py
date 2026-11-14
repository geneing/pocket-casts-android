"""Retrain on the current corpus and publish the bundle into the Android module.

Run after collection has added episodes; the parity test uses the same fixtures.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADWARE = HERE.parents[1]
REPO = ADWARE.parents[1]
BUNDLE = HERE / "bundle"
ASSETS = REPO / "modules/services/repositories/src/main/assets/ad_detector"
TEST_RES = REPO / "modules/services/repositories/src/test/resources"


def main() -> None:
    subprocess.run(["uv", "run", "python", "-u", "train.py", "--out-dir", str(BUNDLE)], cwd=HERE, check=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    (TEST_RES / "ad_detector").mkdir(parents=True, exist_ok=True)
    shutil.copy2(BUNDLE / "model.json", ASSETS / "model.json")
    shutil.copy2(BUNDLE / "model.json", TEST_RES / "ad_detector/model.json")
    shutil.copy2(BUNDLE / "fixtures.json", TEST_RES / "ad_detector_fixtures.json")
    print(f"published {BUNDLE / 'model.json'} to {ASSETS}")


if __name__ == "__main__":
    main()
