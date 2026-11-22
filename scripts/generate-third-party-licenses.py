#!/usr/bin/env python3
"""Combine checked-in third-party notices for Compose resource packaging."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "composeApp/src/commonMain/composeResources/files/third_party_licenses.txt"
parts = [(root / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")]
for source in sorted((root / "licenses").glob("*.txt")):
    parts.append(f"\n{'=' * 72}\n{source.name}\n{'=' * 72}\n")
    parts.append(source.read_text(encoding="utf-8"))
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("\n".join(parts), encoding="utf-8")
print(output.relative_to(root))
