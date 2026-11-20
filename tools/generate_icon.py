#!/usr/bin/env python3
"""Generate the 256x256 camera icon used by the Forge mod list.

Usage: python3 tools/generate_icon.py
Requires: Pillow
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 256
SS = 4  # supersampling factor for smooth edges
W = SIZE * SS


def s(value):
    return int(round(value * SS))


def main():
    image = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    body = (32, 82, 224, 198)
    # soft drop shadow
    draw.rounded_rectangle(
        (s(body[0] + 2), s(body[1] + 10), s(body[2] + 2), s(body[3] + 12)),
        radius=s(34), fill=(0, 0, 0, 45)
    )
    # camera body
    draw.rounded_rectangle(
        (s(body[0]), s(body[1]), s(body[2]), s(body[3])),
        radius=s(32), fill=(38, 49, 67, 255), outline=(92, 112, 140, 255), width=s(5)
    )
    # viewfinder hump
    draw.rounded_rectangle(
        (s(88), s(56), s(146), s(94)),
        radius=s(18), fill=(38, 49, 67, 255), outline=(92, 112, 140, 255), width=s(5)
    )
    # recording lamp
    draw.ellipse((s(52), s(102), s(70), s(120)), fill=(240, 78, 68, 255))
    draw.ellipse((s(57), s(106), s(65), s(114)), fill=(255, 159, 148, 255))
    # grip lines
    for i in range(3):
        y = 124 + i * 18
        draw.rounded_rectangle((s(44), s(y), s(70), s(y + 5)), radius=s(2),
                               fill=(84, 104, 132, 255))
    # lens outer ring
    cx, cy, outer, inner, iris = 162, 145, 50, 38, 27
    draw.ellipse((s(cx - outer), s(cy - outer), s(cx + outer), s(cy + outer)),
                 fill=(15, 22, 32, 255), outline=(120, 145, 176, 255), width=s(5))
    draw.ellipse((s(cx - inner), s(cy - inner), s(cx + inner), s(cy + inner)),
                 fill=(24, 38, 56, 255))
    draw.ellipse((s(cx - iris), s(cy - iris), s(cx + iris), s(cy + iris)),
                 fill=(41, 151, 224, 255))
    draw.ellipse((s(cx - 17), s(cy - 17), s(cx + 17), s(cy + 17)),
                 fill=(12, 22, 38, 255))
    # lens highlight
    draw.ellipse((s(cx - 19), s(cy - 22), s(cx + 1), s(cy - 2)),
                 fill=(158, 226, 255, 210))
    draw.ellipse((s(cx + 8), s(cy + 10), s(cx + 16), s(cy + 18)),
                 fill=(88, 194, 255, 180))

    image = image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    target = Path(__file__).resolve().parents[1] / "src/main/resources/icon.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, "PNG")
    print("wrote", target)


if __name__ == "__main__":
    main()
