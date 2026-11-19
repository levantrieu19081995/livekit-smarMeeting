import re
import os
from pathlib import Path

_UNSAFE = re.compile(r"[^A-Za-z0-9._-]+")


def sanitize_path_part(value: str | None, fallback: str = "unknown") -> str:
    cleaned = _UNSAFE.sub("-", (value or "").strip()).strip(".-")
    return cleaned or fallback


def ensure_relative_to(child: Path, parent: Path) -> Path:
    child_resolved = child.resolve()
    parent_resolved = parent.resolve()
    child_resolved.relative_to(parent_resolved)
    return child_resolved


def to_io_path(path: Path) -> Path:
    """Use Windows extended paths for private storage, never for API path values."""
    if os.name != "nt": return path
    value = str(path.absolute())
    if value.startswith("\\\\?\\"): return Path(value)
    if value.startswith("\\\\"): return Path("\\\\?\\UNC\\" + value[2:])
    return Path("\\\\?\\" + value)

