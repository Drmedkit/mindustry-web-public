#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import sys
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
IMPORTER = SCRIPTS / "import-generated-atlases.py"
GENERATOR = SCRIPTS / "generate-asset-manifest.mjs"
MAIN_BYTES = b"synthetic-main-atlas\x00\x01"
FALLBACK_BYTES = b"synthetic-fallback-atlas\x02\x03"


def add_file(archive: zipfile.ZipFile, name: str, contents: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, contents)


class ImportGeneratedAtlasesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "project"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "Mindustry" / "core" / "assets" / "sprites").mkdir(parents=True)
        (self.root / "browser-port").mkdir()
        shutil.copy2(GENERATOR, self.root / "scripts" / GENERATOR.name)
        (self.root / "Mindustry" / "core" / "assets" / "seed.txt").write_text(
            "seed\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_import(self, archive: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(IMPORTER),
                str(archive),
                "--project-root",
                str(self.root),
                *arguments,
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def make_archive(self, name: str = "Mindustry.jar") -> Path:
        archive_path = Path(self.temporary.name) / name
        with zipfile.ZipFile(archive_path, "w") as archive:
            add_file(archive, "release/assets/sprites/sprites.aatls", MAIN_BYTES)
            add_file(
                archive,
                "release/assets/sprites/fallback/sprites.aatls",
                FALLBACK_BYTES,
            )
        return archive_path

    def test_import_provenance_manifest_and_overwrite_guard(self) -> None:
        archive = self.make_archive()
        result = self.run_import(archive)
        self.assertEqual(result.returncode, 0, result.stderr)

        assets = self.root / "Mindustry" / "core" / "assets"
        self.assertEqual((assets / "sprites" / "sprites.aatls").read_bytes(), MAIN_BYTES)
        self.assertEqual(
            (assets / "sprites" / "fallback" / "sprites.aatls").read_bytes(),
            FALLBACK_BYTES,
        )

        provenance_path = self.root / "browser-port" / "generated-atlas-provenance.json"
        provenance_bytes = provenance_path.read_bytes()
        provenance = json.loads(provenance_bytes)
        self.assertEqual(
            provenance["source"]["archiveSha256"],
            hashlib.sha256(archive.read_bytes()).hexdigest(),
        )
        entries = {entry["assetPath"]: entry for entry in provenance["entries"]}
        self.assertEqual(
            entries["sprites/sprites.aatls"]["sha256"],
            hashlib.sha256(MAIN_BYTES).hexdigest(),
        )
        self.assertEqual(
            entries["sprites/fallback/sprites.aatls"]["sha256"],
            hashlib.sha256(FALLBACK_BYTES).hexdigest(),
        )

        manifest_path = self.root / "browser-port" / "assets-manifest.json"
        manifest_bytes = manifest_path.read_bytes()
        manifest = json.loads(manifest_bytes)
        required = {item["path"]: item["present"] for item in manifest["requiredGeneratedAssets"]}
        self.assertIs(required["sprites/sprites.aatls"], True)
        self.assertIs(required["sprites/fallback/sprites.aatls"], True)

        refused = self.run_import(archive)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("refusing to overwrite", refused.stderr)
        self.assertEqual(provenance_path.read_bytes(), provenance_bytes)
        self.assertEqual(manifest_path.read_bytes(), manifest_bytes)

        overwritten = self.run_import(archive, "--overwrite")
        self.assertEqual(overwritten.returncode, 0, overwritten.stderr)
        self.assertEqual(provenance_path.read_bytes(), provenance_bytes)
        self.assertEqual(manifest_path.read_bytes(), manifest_bytes)

    def test_rejects_ambiguous_suffixes_without_writing(self) -> None:
        archive_path = Path(self.temporary.name) / "ambiguous.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            add_file(archive, "sprites/sprites.aatls", MAIN_BYTES)
            add_file(archive, "assets/sprites/sprites.aatls", MAIN_BYTES)
            add_file(archive, "sprites/fallback/sprites.aatls", FALLBACK_BYTES)

        result = self.run_import(archive_path)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ambiguous", result.stderr)
        self.assertFalse(
            (self.root / "Mindustry" / "core" / "assets" / "sprites" / "sprites.aatls").exists()
        )

    def test_rejects_path_traversal_anywhere_in_archive(self) -> None:
        archive_path = Path(self.temporary.name) / "traversal.apk"
        with zipfile.ZipFile(archive_path, "w") as archive:
            add_file(archive, "assets/sprites/sprites.aatls", MAIN_BYTES)
            add_file(archive, "assets/sprites/fallback/sprites.aatls", FALLBACK_BYTES)
            add_file(archive, "../outside.txt", b"no")

        result = self.run_import(archive_path)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsafe path component", result.stderr)
        self.assertFalse((Path(self.temporary.name) / "outside.txt").exists())


if __name__ == "__main__":
    unittest.main()
