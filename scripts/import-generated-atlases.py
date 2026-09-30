#!/usr/bin/env python3
"""Safely import generated Mindustry atlases from a ZIP-compatible release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile


SOURCE_COMMIT = "439799ce8ff1480d1d169c5ba7ff3def422999de"
ATLAS_PATHS = (
    "sprites/sprites.aatls",
    "sprites/fallback/sprites.aatls",
)
SUPPORTED_SUFFIXES = {".jar", ".zip", ".apk"}
MAX_ATLAS_BYTES = 512 * 1024 * 1024
COPY_CHUNK_BYTES = 1024 * 1024


class ImportError_(RuntimeError):
    """A user-actionable import failure."""


def sha256_file(file_object) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    while True:
        chunk = file_object.read(COPY_CHUNK_BYTES)
        if not chunk:
            break
        digest.update(chunk)
        total += len(chunk)
    return digest.hexdigest(), total


def validate_archive_name(name: str) -> str:
    if not name or "\x00" in name:
        raise ImportError_("archive contains an empty or NUL-containing entry name")
    if "\\" in name:
        raise ImportError_(f"archive entry uses a backslash path separator: {name!r}")
    if name.startswith("/") or name.startswith("//"):
        raise ImportError_(f"archive entry is absolute: {name!r}")

    without_directory_slash = name[:-1] if name.endswith("/") else name
    if not without_directory_slash:
        raise ImportError_(f"archive entry is not a relative file path: {name!r}")

    parts = without_directory_slash.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise ImportError_(f"archive entry contains an unsafe path component: {name!r}")
    if re.fullmatch(r"[A-Za-z]:.*", parts[0]):
        raise ImportError_(f"archive entry contains a Windows drive path: {name!r}")
    return without_directory_slash


def inspect_archive(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    selected: dict[str, list[zipfile.ZipInfo]] = {path: [] for path in ATLAS_PATHS}
    seen_names: set[str] = set()

    for info in archive.infolist():
        safe_name = validate_archive_name(info.filename)
        if safe_name in seen_names:
            raise ImportError_(f"archive contains a duplicate entry: {safe_name!r}")
        seen_names.add(safe_name)

        if info.flag_bits & 0x1:
            raise ImportError_(f"archive contains an encrypted entry: {safe_name!r}")
        if info.is_dir():
            continue

        for asset_path in ATLAS_PATHS:
            # Official packages have used the archive root, assets/, and build/source
            # directory prefixes. Match only complete path-component suffixes.
            if safe_name == asset_path or safe_name.endswith(f"/{asset_path}"):
                selected[asset_path].append(info)

    resolved: dict[str, zipfile.ZipInfo] = {}
    for asset_path, matches in selected.items():
        if not matches:
            raise ImportError_(f"archive is missing required entry suffix: {asset_path}")
        if len(matches) != 1:
            names = ", ".join(repr(match.filename) for match in matches)
            raise ImportError_(f"archive is ambiguous for {asset_path}: {names}")

        info = matches[0]
        unix_mode = (info.external_attr >> 16) & 0xFFFF
        file_type = stat.S_IFMT(unix_mode)
        if file_type not in {0, stat.S_IFREG}:
            raise ImportError_(f"required archive entry is not a regular file: {info.filename!r}")
        if info.file_size <= 0:
            raise ImportError_(f"required archive entry is empty: {info.filename!r}")
        if info.file_size > MAX_ATLAS_BYTES:
            raise ImportError_(
                f"required archive entry exceeds the {MAX_ATLAS_BYTES}-byte safety limit: "
                f"{info.filename!r}"
            )
        resolved[asset_path] = info

    return resolved


def copy_archive_entry(
    archive: zipfile.ZipFile, info: zipfile.ZipInfo, destination: Path
) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with archive.open(info, "r") as source, destination.open("xb") as output:
        while True:
            chunk = source.read(COPY_CHUNK_BYTES)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_ATLAS_BYTES:
                raise ImportError_(f"archive entry expanded beyond its safety limit: {info.filename!r}")
            digest.update(chunk)
            output.write(chunk)
        output.flush()
        os.fsync(output.fileno())

    if total != info.file_size:
        raise ImportError_(
            f"archive entry size changed while reading {info.filename!r}: "
            f"expected {info.file_size}, read {total}"
        )
    return digest.hexdigest(), total


def path_exists(path: Path) -> bool:
    return os.path.lexists(path)


def require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise ImportError_(f"{label} is not a regular file: {path}")


def make_safe_parents(parent: Path, boundary: Path) -> list[Path]:
    try:
        relative = parent.relative_to(boundary)
    except ValueError as error:
        raise ImportError_(f"destination escapes asset root: {parent}") from error

    current = boundary
    created: list[Path] = []
    for component in relative.parts:
        current = current / component
        if path_exists(current):
            if current.is_symlink() or not current.is_dir():
                raise ImportError_(f"destination parent is not a real directory: {current}")
        else:
            current.mkdir()
            created.append(current)
    return created


def copy_backup(source: Path, destination: Path) -> None:
    with source.open("rb") as input_, destination.open("xb") as output:
        shutil.copyfileobj(input_, output, COPY_CHUNK_BYTES)
        output.flush()
        os.fsync(output.fileno())


def write_json(path: Path, value: object) -> None:
    encoded = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
    with path.open("xb") as output:
        output.write(encoded)
        output.flush()
        os.fsync(output.fileno())


def validate_manifest(manifest_path: Path, imported: dict[str, dict[str, object]]) -> None:
    try:
        manifest_bytes = manifest_path.read_bytes()
        manifest = json.loads(manifest_bytes)
    except (OSError, json.JSONDecodeError) as error:
        raise ImportError_(f"generated asset manifest is unreadable: {error}") from error

    if manifest.get("source", {}).get("commit") != SOURCE_COMMIT:
        raise ImportError_("generated asset manifest does not identify the pinned source commit")

    required = {
        item.get("path"): item.get("present")
        for item in manifest.get("requiredGeneratedAssets", [])
        if isinstance(item, dict)
    }
    files = {
        item.get("path"): item
        for item in manifest.get("files", [])
        if isinstance(item, dict)
    }
    for asset_path in ATLAS_PATHS:
        if required.get(asset_path) is not True:
            raise ImportError_(f"generated manifest still marks {asset_path} as absent")
        entry = files.get(asset_path)
        expected = imported[asset_path]
        if not entry:
            raise ImportError_(f"generated manifest has no file entry for {asset_path}")
        if entry.get("sha256") != expected["sha256"] or entry.get("bytes") != expected["bytes"]:
            raise ImportError_(f"generated manifest hash/size mismatch for {asset_path}")


def run_manifest_generator(project_root: Path, output_path: Path) -> str:
    generator = project_root / "scripts" / "generate-asset-manifest.mjs"
    if not generator.is_file():
        raise ImportError_(f"asset manifest generator is missing: {generator}")
    node = shutil.which("node")
    if not node:
        raise ImportError_("node is required to regenerate the asset manifest")

    result = subprocess.run(
        [node, str(generator), "--output", str(output_path)],
        cwd=project_root,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ImportError_(f"asset manifest generation failed: {detail}")
    return result.stdout.strip()


def restore_paths(
    changed: list[Path], originals: dict[Path, Path | None]
) -> list[str]:
    errors: list[str] = []
    for destination in reversed(changed):
        backup = originals[destination]
        try:
            if backup is None:
                destination.unlink(missing_ok=True)
            else:
                os.replace(backup, destination)
        except OSError as error:
            errors.append(f"could not restore {destination}: {error}")
    return errors


def import_atlases(archive_path: Path, project_root: Path, overwrite: bool) -> None:
    archive_path = archive_path.expanduser().resolve()
    project_root = project_root.expanduser().resolve()
    if archive_path.suffix.lower() not in SUPPORTED_SUFFIXES:
        choices = ", ".join(sorted(SUPPORTED_SUFFIXES))
        raise ImportError_(f"archive must have one of these extensions: {choices}")
    if not archive_path.is_file():
        raise ImportError_(f"archive is not a regular file: {archive_path}")

    asset_root = project_root / "Mindustry" / "core" / "assets"
    browser_root = project_root / "browser-port"
    manifest_path = browser_root / "assets-manifest.json"
    provenance_path = browser_root / "generated-atlas-provenance.json"
    if asset_root.is_symlink() or not asset_root.is_dir():
        raise ImportError_(f"pinned asset root is missing or unsafe: {asset_root}")
    if browser_root.is_symlink() or not browser_root.is_dir():
        raise ImportError_(f"browser-port directory is missing or unsafe: {browser_root}")

    destinations = {path: asset_root / Path(path) for path in ATLAS_PATHS}
    existing_atlases = [path for path in destinations.values() if path_exists(path)]
    for path in existing_atlases:
        require_regular_file(path, "existing atlas")
    if existing_atlases and not overwrite:
        listed = ", ".join(str(path) for path in existing_atlases)
        raise ImportError_(f"refusing to overwrite existing atlas(es): {listed}; pass --overwrite")

    for metadata_path in (manifest_path, provenance_path):
        if path_exists(metadata_path):
            require_regular_file(metadata_path, "existing metadata")

    with tempfile.TemporaryDirectory(prefix=".atlas-import-", dir=browser_root) as temporary:
        staging = Path(temporary)
        imported: dict[str, dict[str, object]] = {}

        try:
            with archive_path.open("rb") as archive_file:
                archive_stat_before = os.fstat(archive_file.fileno())
                archive_sha256, archive_bytes = sha256_file(archive_file)
                archive_file.seek(0)
                with zipfile.ZipFile(archive_file, "r") as archive:
                    selected = inspect_archive(archive)
                    for index, asset_path in enumerate(ATLAS_PATHS):
                        info = selected[asset_path]
                        staged_path = staging / f"atlas-{index}.aatls"
                        digest, size = copy_archive_entry(archive, info, staged_path)
                        imported[asset_path] = {
                            "archiveEntry": info.filename,
                            "assetPath": asset_path,
                            "bytes": size,
                            "sha256": digest,
                        }

                archive_file.seek(0)
                verification_sha256, verification_bytes = sha256_file(archive_file)
                archive_stat_after = os.fstat(archive_file.fileno())
                if (
                    verification_sha256 != archive_sha256
                    or verification_bytes != archive_bytes
                    or archive_stat_before.st_size != archive_stat_after.st_size
                    or archive_stat_before.st_mtime_ns != archive_stat_after.st_mtime_ns
                ):
                    raise ImportError_("archive changed while it was being inspected")
        except zipfile.BadZipFile as error:
            raise ImportError_(f"not a valid ZIP-compatible archive: {error}") from error
        except (OSError, RuntimeError) as error:
            if isinstance(error, ImportError_):
                raise
            raise ImportError_(f"could not inspect archive: {error}") from error

        transaction_paths = [*destinations.values(), manifest_path, provenance_path]
        originals: dict[Path, Path | None] = {}
        for index, destination in enumerate(transaction_paths):
            if path_exists(destination):
                backup = staging / f"original-{index}"
                copy_backup(destination, backup)
                originals[destination] = backup
            else:
                originals[destination] = None

        created_directories: list[Path] = []
        changed: list[Path] = []
        try:
            for index, asset_path in enumerate(ATLAS_PATHS):
                destination = destinations[asset_path]
                created_directories.extend(make_safe_parents(destination.parent, asset_root))
                os.replace(staging / f"atlas-{index}.aatls", destination)
                changed.append(destination)

            staged_manifest = staging / "assets-manifest.json"
            generator_output = run_manifest_generator(project_root, staged_manifest)
            validate_manifest(staged_manifest, imported)
            with staged_manifest.open("rb") as manifest_file:
                manifest_sha256, _ = sha256_file(manifest_file)

            provenance = {
                "assetManifest": {
                    "path": "browser-port/assets-manifest.json",
                    "sha256": manifest_sha256,
                },
                "entries": [imported[path] for path in ATLAS_PATHS],
                "purpose": "temporary browser-port bootstrap",
                "schemaVersion": 1,
                "source": {
                    "archiveBytes": archive_bytes,
                    "archiveSha256": archive_sha256,
                    "project": "Anuken/Mindustry",
                },
                "target": {
                    "assetRoot": "Mindustry/core/assets",
                    "sourceCommit": SOURCE_COMMIT,
                },
            }
            staged_provenance = staging / "generated-atlas-provenance.json"
            write_json(staged_provenance, provenance)

            os.replace(staged_manifest, manifest_path)
            changed.append(manifest_path)
            os.replace(staged_provenance, provenance_path)
            changed.append(provenance_path)
        except Exception as error:
            restoration_errors = restore_paths(changed, originals)
            for directory in reversed(created_directories):
                try:
                    directory.rmdir()
                except OSError:
                    pass
            if restoration_errors:
                detail = "; ".join(restoration_errors)
                raise ImportError_(f"import failed ({error}); rollback was incomplete: {detail}") from error
            if isinstance(error, ImportError_):
                raise
            raise ImportError_(f"import failed and was rolled back: {error}") from error

    print(f"Imported {len(ATLAS_PATHS)} generated atlases from archive {archive_sha256}.")
    print(f"Updated {manifest_path} and {provenance_path}.")
    if generator_output:
        print(generator_output)


def parse_arguments(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Import the two generated Mindustry sprite atlases from a user-supplied "
            "JAR, ZIP, or APK."
        )
    )
    parser.add_argument("archive", type=Path, help="official Mindustry JAR/ZIP/APK")
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace atlases that already exist (never implied)",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help=argparse.SUPPRESS,
    )
    return parser.parse_args(arguments)


def main(arguments: list[str]) -> int:
    options = parse_arguments(arguments)
    try:
        import_atlases(options.archive, options.project_root, options.overwrite)
    except ImportError_ as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
