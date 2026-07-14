from __future__ import annotations

import importlib.util
import sys
import types
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2] / "custom_components" / "hearth"
_PKG = "hearth_proto"


def _ensure_loaded() -> None:
    if _PKG in sys.modules:
        return
    pkg = types.ModuleType(_PKG)
    pkg.__path__ = [str(_ROOT)]  # make it a package so `from .codec import` resolves
    sys.modules[_PKG] = pkg
    for name in ("codec", "client"):  # codec first: client does `from .codec import`
        path = _ROOT / f"{name}.py"
        if not path.exists():
            continue
        spec = importlib.util.spec_from_file_location(f"{_PKG}.{name}", path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[f"{_PKG}.{name}"] = module
        spec.loader.exec_module(module)


_ensure_loaded()
