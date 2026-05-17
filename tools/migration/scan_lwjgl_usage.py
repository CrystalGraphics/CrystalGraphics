#!/usr/bin/env python3
"""
scan_lwjgl_usage.py — LWJGL Member Audit Scanner for CrystalGraphics core/
============================================================================
Scans all .java files under core/src/main/java/ and produces a complete inventory
of every LWJGL member used: imports, method calls, constants, and type references.

This feeds T1 (CgGlDispatch gap analysis) and validates T3 (Static-Transform
pattern coverage). Run it once before touching anything.

Usage:
    python tools/migration/scan_lwjgl_usage.py
    python tools/migration/scan_lwjgl_usage.py --output tools/migration/lwjgl_audit.json
    python tools/migration/scan_lwjgl_usage.py --project-root "X:\\projects\\CrystalGUI\\CrystalGraphics"
    python tools/migration/scan_lwjgl_usage.py --quiet       (JSON only, no summary table)
    python tools/migration/scan_lwjgl_usage.py --gaps-only   (only print CgGlDispatch gap list)

Output (JSON + human-readable summary to stderr):
  - Per-LWJGL-class breakdown: every method and constant used, with file+line references
  - ARB/EXT suffix methods: identified for special-case transform rules
  - LWJGL2 type references: ContextCapabilities, Display, BufferUtils, LWJGLException
  - CgGlDispatch gap list: methods called in core that CgGlDispatch does not yet declare
  - File-level summary: which files still need migration (sorted by LWJGL import count)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path


# ============================================================================
# LWJGL class patterns we care about
# ============================================================================

# GL version classes: GL11 … GL43, GL45
GL_VERSION_PATTERN = re.compile(r"\bGL\d+\b")

# ARB extension classes (method calls)
ARB_CLASS_PATTERN = re.compile(
    r"\b(ARBShaderObjects|ARBVertexShader|ARBFragmentShader|ARBFramebufferObject|"
    r"ARBVertexArrayObject|ARBInstancedArrays|ARBDrawInstanced|ARBSamplerObjects|"
    r"ARBMapBufferRange|ARBShaderStorageBufferObject|ARBUniformBufferObject|"
    r"ARBSync|ARBMultisample|ARBTextureMultisample|ARBDepthClamp|ARBSeamlessCubemap|"
    r"ARBBlendFuncExtended|ARBTextureBufferObject)\b"
)

# EXT extension classes
EXT_CLASS_PATTERN = re.compile(
    r"\b(EXTFramebufferObject|EXTFramebufferMultisample|EXTFramebufferBlit|"
    r"EXTTextureArray|EXTTextureBufferObject|EXTTransformFeedback)\b"
)

# LWJGL2 utility / non-GL type references
LWJGL_UTIL_TYPES = {
    "ContextCapabilities": "lwjgl2-type",
    "Display": "lwjgl2-type",
    "BufferUtils": "lwjgl-bufferutils",
    "LWJGLException": "lwjgl2-exception",
    "GLContext": "lwjgl2-type",
}

# Import line pattern: captures the simple class name
IMPORT_PATTERN = re.compile(
    r"^\s*import\s+org\.lwjgl(?:\.opengl)?\.([A-Za-z0-9_*]+)\s*;", re.MULTILINE
)

# Method call: ClassName.methodName(
# Captures: (class_name, method_name)
METHOD_CALL_PATTERN = re.compile(
    r"\b([A-Z][A-Za-z0-9]+)\.(gl[A-Za-z0-9]+|is[A-Za-z0-9]+|create[A-Za-z0-9]+|"
    r"allocate[A-Za-z0-9]+)\s*\("
)

# Constant reference: ClassName.GL_SOMETHING
CONSTANT_PATTERN = re.compile(r"\b([A-Z][A-Za-z0-9]+)\.(GL_[A-Z0-9_]+)\b")

# ARB suffix method: e.g. glCreateShaderObjectARB, glShaderSourceARB
ARB_SUFFIX_METHOD = re.compile(r"\bgl([A-Za-z0-9]+)(ARB|EXT)\s*\(")

# All LWJGL classes (any import from org.lwjgl or org.lwjgl.opengl)
KNOWN_LWJGL_PREFIXES = ("GL", "ARB", "EXT", "NV", "ATI", "AMD")


def is_lwjgl_class(name: str) -> bool:
    """True if the class name looks like an LWJGL OpenGL class."""
    return (
        any(name.startswith(p) for p in KNOWN_LWJGL_PREFIXES)
        or name in LWJGL_UTIL_TYPES
    )


# ============================================================================
# CgGlDispatch reader
# ============================================================================


def load_dispatch_methods(project_root: Path) -> set:
    """Parse CgGlDispatch.java and return the set of declared abstract method names."""
    candidates = [
        project_root
        / "platform"
        / "src"
        / "main"
        / "java"
        / "com"
        / "crystalgraphics"
        / "platform"
        / "gl"
        / "CgGlDispatch.java",
    ]
    for path in candidates:
        if path.exists():
            return _extract_method_names(path)

    # Fallback: glob search
    for p in project_root.rglob("CgGlDispatch.java"):
        return _extract_method_names(p)

    return set()


def _extract_method_names(path: Path) -> set:
    """Extract all declared method names from a Java file (public + abstract)."""
    text = path.read_text(encoding="utf-8", errors="replace")
    # Match: (abstract )? public/protected returnType methodName(
    pattern = re.compile(
        r"(?:public|protected)\s+(?:abstract\s+)?[\w<>\[\]]+\s+(gl\w+|bind\w+|gen\w+|delete\w+|"
        r"draw\w+|frame\w+|check\w+|blit\w+|renderbuffer\w+|is\w+)\s*\(",
        re.MULTILINE,
    )
    return {m.group(1) for m in pattern.finditer(text)}


# ============================================================================
# Per-file scanner
# ============================================================================


def scan_file(path: Path, rel_path: str) -> dict:
    """Scan a single .java file for all LWJGL usage."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except (IOError, OSError) as e:
        return {"file": rel_path, "error": str(e)}

    lines = text.splitlines()

    # ---- Imports ----
    imports = []
    for m in IMPORT_PATTERN.finditer(text):
        imports.append(m.group(1))

    # ---- Line-by-line scan for calls and constants ----
    method_calls = defaultdict(list)  # class -> [(method, line_no)]
    constants = defaultdict(list)  # class -> [(constant, line_no)]
    arb_suffix = []  # [(full_call_name, line_no)]
    type_refs = defaultdict(list)  # util_type -> [line_no]

    for line_no, line in enumerate(lines, start=1):
        # Skip import lines (already captured)
        stripped = line.strip()
        if (
            stripped.startswith("import ")
            or stripped.startswith("//")
            or stripped.startswith("*")
        ):
            continue

        # Method calls
        for m in METHOD_CALL_PATTERN.finditer(line):
            cls, method = m.group(1), m.group(2)
            if is_lwjgl_class(cls):
                method_calls[cls].append((method, line_no))

        # Constants
        for m in CONSTANT_PATTERN.finditer(line):
            cls, const = m.group(1), m.group(2)
            if is_lwjgl_class(cls):
                constants[cls].append((const, line_no))

        # ARB/EXT suffix methods (e.g. glCreateShaderObjectARB)
        for m in ARB_SUFFIX_METHOD.finditer(line):
            full = f"gl{m.group(1)}{m.group(2)}"
            arb_suffix.append((full, line_no))

        # LWJGL2 utility type references
        for util_type in LWJGL_UTIL_TYPES:
            if re.search(r"\b" + re.escape(util_type) + r"\b", line):
                type_refs[util_type].append(line_no)

    return {
        "file": rel_path,
        "imports": sorted(set(imports)),
        "method_calls": {k: v for k, v in method_calls.items()},
        "constants": {k: v for k, v in constants.items()},
        "arb_suffix": arb_suffix,
        "type_refs": {k: v for k, v in type_refs.items()},
        "lwjgl_import_count": len(set(imports)),
    }


# ============================================================================
# Aggregator
# ============================================================================


def aggregate(file_results: list) -> dict:
    """Aggregate per-file results into a project-wide usage map."""
    # class -> method -> list of {file, lines}
    all_methods = defaultdict(lambda: defaultdict(list))
    all_constants = defaultdict(lambda: defaultdict(list))
    all_arb_suffix = defaultdict(list)  # full_name -> [{file, lines}]
    all_type_refs = defaultdict(list)  # util_type -> [{file, lines}]

    for r in file_results:
        if "error" in r:
            continue
        f = r["file"]

        for cls, calls in r["method_calls"].items():
            for method, line_no in calls:
                all_methods[cls][method].append({"file": f, "line": line_no})

        for cls, consts in r["constants"].items():
            for const, line_no in consts:
                all_constants[cls][const].append({"file": f, "line": line_no})

        for full_name, line_no in r["arb_suffix"]:
            all_arb_suffix[full_name].append({"file": f, "line": line_no})

        for util_type, line_nos in r["type_refs"].items():
            for line_no in line_nos:
                all_type_refs[util_type].append({"file": f, "line": line_no})

    return {
        "methods": {k: dict(v) for k, v in all_methods.items()},
        "constants": {k: dict(v) for k, v in all_constants.items()},
        "arb_suffix": dict(all_arb_suffix),
        "type_refs": dict(all_type_refs),
    }


def compute_gaps(aggregated: dict, dispatch_methods: set) -> list:
    """
    Compute the CgGlDispatch gap list: methods called in core that
    CgGlDispatch does not yet declare.

    Returns list of dicts: {method, lwjgl_class, files}
    """
    gaps = []
    seen = set()

    for cls, methods in aggregated["methods"].items():
        for method, refs in methods.items():
            if method in seen:
                continue
            seen.add(method)

            # Normalize: strip ARB/EXT suffix for comparison
            normalized = re.sub(r"(ARB|EXT)$", "", method)
            # FBO methods in CgGlDispatch drop the 'gl' prefix
            fbo_name = normalized[2:] if normalized.startswith("gl") else normalized

            in_dispatch = (
                normalized in dispatch_methods
                or fbo_name in dispatch_methods
                or method in dispatch_methods
            )

            if not in_dispatch:
                gaps.append(
                    {
                        "method": method,
                        "normalized": normalized,
                        "lwjgl_class": cls,
                        "files": [r["file"] for r in refs],
                        "usage_count": len(refs),
                    }
                )

    # Also check ARB suffix methods
    for full_name, refs in aggregated["arb_suffix"].items():
        normalized = re.sub(r"(ARB|EXT)$", "", full_name)
        fbo_name = normalized[2:] if normalized.startswith("gl") else normalized
        in_dispatch = (
            normalized in dispatch_methods
            or fbo_name in dispatch_methods
            or full_name in dispatch_methods
        )
        if not in_dispatch and normalized not in seen:
            seen.add(normalized)
            gaps.append(
                {
                    "method": full_name,
                    "normalized": normalized,
                    "lwjgl_class": "ARBShaderObjects/suffix",
                    "files": [r["file"] for r in refs],
                    "usage_count": len(refs),
                    "note": "ARB suffix method — needs explicit Static-Transform rule",
                }
            )

    gaps.sort(key=lambda x: (-x["usage_count"], x["normalized"]))
    return gaps


# ============================================================================
# Human-readable summary
# ============================================================================


def print_summary(file_results: list, aggregated: dict, gaps: list, file=sys.stderr):
    files_with_lwjgl = [r for r in file_results if r.get("lwjgl_import_count", 0) > 0]
    total_files = len(file_results)
    total_imports = sum(r.get("lwjgl_import_count", 0) for r in file_results)

    sep = "═" * 80
    print(f"\n{sep}", file=file)
    print(f"  LWJGL Audit Report — CrystalGraphics core/", file=file)
    print(sep, file=file)
    print(f"  Total .java files scanned:     {total_files:>5}", file=file)
    print(f"  Files with LWJGL imports:      {len(files_with_lwjgl):>5}", file=file)
    print(f"  Distinct LWJGL import names:   {total_imports:>5}", file=file)
    print(f"  CgGlDispatch gap count:        {len(gaps):>5}", file=file)
    print(sep, file=file)

    # ---- Per-class breakdown ----
    print("\n  LWJGL Classes Used (methods + constants):", file=file)
    all_classes = sorted(
        set(aggregated["methods"].keys()) | set(aggregated["constants"].keys())
    )
    for cls in all_classes:
        methods = aggregated["methods"].get(cls, {})
        constants = aggregated["constants"].get(cls, {})
        files_set = set()
        for refs in list(methods.values()) + list(constants.values()):
            files_set.update(r["file"] for r in refs)
        print(
            f"    {cls:<40} {len(methods):>3} methods, "
            f"{len(constants):>3} constants, {len(files_set):>3} files",
            file=file,
        )

    # ---- ARB suffix methods ----
    if aggregated["arb_suffix"]:
        print(
            f"\n  ARB/EXT Suffix Methods (need explicit Static-Transform rules):",
            file=file,
        )
        for full_name, refs in sorted(aggregated["arb_suffix"].items()):
            files_set = {r["file"] for r in refs}
            print(f"    {full_name:<45} in {len(files_set)} file(s)", file=file)

    # ---- LWJGL2 utility type references ----
    if aggregated["type_refs"]:
        print(
            f"\n  LWJGL2 Utility Type References (surgical pa_editor.py fixes needed):",
            file=file,
        )
        for util_type, refs in sorted(aggregated["type_refs"].items()):
            files_set = {r["file"] for r in refs}
            print(
                f"    {util_type:<30} in {len(files_set)} file(s): "
                f"{', '.join(sorted(f.split('/')[-1] for f in files_set)[:5])}",
                file=file,
            )

    # ---- Gap list ----
    if gaps:
        print(f"\n  CgGlDispatch GAP LIST ({len(gaps)} methods missing):", file=file)
        for g in gaps:
            note = f"  ← {g['note']}" if "note" in g else ""
            print(
                f"    {g['normalized']:<45} (used {g['usage_count']}x in "
                f"{len(g['files'])} file(s)){note}",
                file=file,
            )
    else:
        print(
            "\n  ✓ No CgGlDispatch gaps detected (dispatch is already complete)",
            file=file,
        )

    # ---- Files by LWJGL import count (descending) ----
    print(f"\n  Files Sorted by LWJGL Import Count:", file=file)
    sorted_files = sorted(files_with_lwjgl, key=lambda r: -r["lwjgl_import_count"])
    for r in sorted_files:
        classes = sorted(set(r.get("imports", [])))
        print(
            f"    {r['lwjgl_import_count']:>3}  {r['file']:<70}"
            f"  [{', '.join(classes[:4])}{'...' if len(classes) > 4 else ''}]",
            file=file,
        )

    print(f"\n{sep}\n", file=file)


# ============================================================================
# Main
# ============================================================================


def find_project_root(start: Path) -> Path:
    """Walk up from start to find settings.gradle."""
    d = start
    for _ in range(10):
        if (d / "settings.gradle").exists():
            return d
        parent = d.parent
        if parent == d:
            break
        d = parent
    return start


def main():
    script_dir = Path(__file__).resolve().parent
    default_root = find_project_root(script_dir.parent.parent)

    ap = argparse.ArgumentParser(
        description="LWJGL Member Audit Scanner — CrystalGraphics core/",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Examples:
  %(prog)s
  %(prog)s --output tools/migration/lwjgl_audit.json
  %(prog)s --project-root "X:\\\\projects\\\\CrystalGUI\\\\CrystalGraphics"
  %(prog)s --quiet
  %(prog)s --gaps-only
""",
    )
    ap.add_argument(
        "--project-root", default=str(default_root), help="Project root directory"
    )
    ap.add_argument(
        "--scan-dir",
        default="core/src/main/java",
        help="Directory to scan (relative to project root)",
    )
    ap.add_argument(
        "--output",
        "-o",
        default=None,
        help="Write JSON output to file (default: stdout)",
    )
    ap.add_argument(
        "--quiet",
        "-q",
        action="store_true",
        help="Suppress human-readable summary table",
    )
    ap.add_argument(
        "--gaps-only",
        action="store_true",
        help="Only print the CgGlDispatch gap list, then exit",
    )
    args = ap.parse_args()

    project_root = Path(args.project_root).resolve()
    scan_root = project_root / args.scan_dir

    if not scan_root.exists():
        print(f"ERROR: Scan directory not found: {scan_root}", file=sys.stderr)
        sys.exit(1)

    # Load CgGlDispatch method signatures for gap analysis
    dispatch_methods = load_dispatch_methods(project_root)
    if dispatch_methods:
        print(
            f"Loaded {len(dispatch_methods)} CgGlDispatch method names", file=sys.stderr
        )
    else:
        print(
            "WARNING: CgGlDispatch.java not found — gap list will show ALL methods",
            file=sys.stderr,
        )

    # Scan files
    java_files = sorted(scan_root.rglob("*.java"))
    print(
        f"Scanning {len(java_files)} .java files under {args.scan_dir}...",
        file=sys.stderr,
    )

    file_results = []
    for path in java_files:
        rel = path.relative_to(scan_root).as_posix()
        result = scan_file(path, rel)
        file_results.append(result)

    # Aggregate
    aggregated = aggregate(file_results)
    gaps = compute_gaps(aggregated, dispatch_methods)

    # Gaps-only mode
    if args.gaps_only:
        print(f"\nCgGlDispatch gap list ({len(gaps)} methods):\n", file=sys.stderr)
        for g in gaps:
            note = f"  # {g['note']}" if "note" in g else ""
            print(f"  {g['normalized']}{note}", file=sys.stderr)
        sys.exit(0)

    # Summary
    if not args.quiet:
        print_summary(file_results, aggregated, gaps)

    # JSON output
    output = {
        "meta": {
            "scanDir": args.scan_dir,
            "totalFiles": len(java_files),
            "filesWithLwjgl": sum(
                1 for r in file_results if r.get("lwjgl_import_count", 0) > 0
            ),
            "dispatchMethodsLoaded": len(dispatch_methods),
            "gapCount": len(gaps),
        },
        "files": file_results,
        "aggregated": aggregated,
        "gaps": gaps,
        "dispatchMethods": sorted(dispatch_methods),
    }

    json_str = json.dumps(output, indent=2, ensure_ascii=False)

    if args.output:
        out_path = (
            project_root / args.output
            if not os.path.isabs(args.output)
            else Path(args.output)
        )
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json_str + "\n", encoding="utf-8")
        print(f"Wrote {out_path}", file=sys.stderr)
    else:
        print(json_str)


if __name__ == "__main__":
    main()
