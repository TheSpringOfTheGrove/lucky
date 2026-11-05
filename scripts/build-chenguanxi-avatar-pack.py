#!/usr/bin/env python3
"""Build the confirmed, customer-specific 60-avatar set from reviewed PNGs.

Usage:
  python scripts/build-chenguanxi-avatar-pack.py ORIGINAL_50 NEW_YOUNG_10 NEW_CAISHEN_10 [REVIEW_DIR]
  python scripts/build-chenguanxi-avatar-pack.py --confirmed CONFIRMED_60_DIR
"""

from __future__ import annotations

import hashlib
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ROOM_DIR = ROOT / "lucky5-ui/src/assets/lottery/player-avatars-chenguanxi"
ACCOUNT_DIR = ROOT / "lucky5-ui/public/chenguanxi/account-avatars"


def sources(original: Path, young: Path, caishen: Path) -> list[tuple[str, Path]]:
    entries = [
        *( (f"new-young-{i:02d}", young / f"young-man-{i:02d}.png") for i in range(1, 11) ),
        *( (f"original-male-{i:02d}", original / f"male-{i:02d}.png") for i in range(11, 21) ),
        *( (f"original-female-{i:02d}", original / f"female-{i:02d}.png") for i in range(1, 11) ),
        *( (f"original-caishen-{i:02d}", original / f"caishen-{i:02d}.png") for i in range(1, 11) ),
        *( (f"original-koi-{i:02d}", original / f"koi-{i:02d}.png") for i in range(1, 11) ),
        *( (f"new-caishen-{i:02d}", caishen / f"web-caishen-{i:02d}.png") for i in range(1, 11) ),
    ]
    if len(entries) != 60 or any(not path.is_file() for _, path in entries):
        raise SystemExit("Expected all 60 reviewed PNG sources; no output was changed")
    digests = [hashlib.sha256(path.read_bytes()).hexdigest() for _, path in entries]
    if len(set(digests)) != 60:
        raise SystemExit("Duplicate source image detected; no output was changed")
    return entries


def confirmed_sources(directory: Path) -> list[tuple[str, Path]]:
    entries = []
    for number in range(1, 61):
        matches = list(directory.glob(f"{number:02d}-*.png"))
        if len(matches) != 1:
            raise SystemExit(f"Expected exactly one confirmed image #{number:02d}")
        entries.append((matches[0].stem[3:], matches[0]))
    return entries


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "--confirmed":
        entries = confirmed_sources(Path(sys.argv[2]))
        review_dir = None
    elif len(sys.argv) in (4, 5):
        original, young, caishen = (Path(value) for value in sys.argv[1:4])
        review_dir = Path(sys.argv[4]) if len(sys.argv) == 5 else None
        entries = sources(original, young, caishen)
    else:
        raise SystemExit(__doc__)
    ROOM_DIR.mkdir(parents=True, exist_ok=True)
    ACCOUNT_DIR.mkdir(parents=True, exist_ok=True)
    if review_dir:
        review_dir.mkdir(parents=True, exist_ok=True)

    manifest = ["# 冠希客户确认头像 60 张", "", "编号 | 来源", "--- | ---"]
    for number, (label, source) in enumerate(entries, 1):
        filename = f"avatar_{number:02d}.jpg"
        with Image.open(source) as image:
            avatar = ImageOps.fit(image.convert("RGB"), (256, 256), Image.Resampling.LANCZOS)
            avatar.save(ROOM_DIR / filename, "JPEG", quality=84, optimize=True, progressive=True)
        shutil.copyfile(ROOM_DIR / filename, ACCOUNT_DIR / filename)
        if review_dir:
            shutil.copyfile(source, review_dir / f"{number:02d}-{label}.png")
        manifest.append(f"{number:02d} | {label}")

    if review_dir:
        (review_dir / "manifest.md").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    print(f"Built {len(entries)} distinct avatars for chen-guan-xi")


if __name__ == "__main__":
    main()
