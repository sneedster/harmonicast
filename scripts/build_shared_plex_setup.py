#!/usr/bin/env python3
"""Build the portable, credential-free setup archive from checked-in assets."""
from pathlib import Path
import argparse
import io
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/app/src/main/assets/shared-plex"
OUTPUT = ASSETS / "harmonicast-plex-setup.zip"


def package_bytes():
    files = {
        "README.txt": (ROOT / "docs/shared-plex-setup/README.txt").read_bytes(),
        "Harmonicast/": b"",
        "Harmonicast/Shared Access Setup/": b"",
        "Harmonicast/Shared Access Setup/01-setup.flac": (ASSETS / "01-setup.flac").read_bytes(),
    }
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16)
            if name.endswith("/"):
                info.external_attr |= 0x10
            archive.writestr(info, data)
    return result.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the bundled ZIP is stale")
    args = parser.parse_args()
    expected = package_bytes()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_bytes() != expected:
            raise SystemExit("Setup ZIP is stale. Run python3 scripts/build_shared_plex_setup.py")
        print("Setup ZIP matches its source files")
    else:
        OUTPUT.write_bytes(expected)
        print(f"Created {OUTPUT} ({len(expected)} bytes)")
