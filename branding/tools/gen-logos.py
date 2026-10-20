#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Derive Energo-Pro logo variants (white/Firefly lockups, marks, favicons) from the supplied logos.

Usage: gen-logos.py <black-stacked.png> <color-horizontal.png> <out-dir>
"""
import sys
from PIL import Image
src_black, src_color, out = sys.argv[1], sys.argv[2], sys.argv[3]
b = Image.open(src_black).convert("RGBA")
alpha = b.split()[3]
# black logo may be opaque white bg: derive ink mask from luminance if alpha is flat
if alpha.getextrema() == (255, 255):
    g = b.convert("L"); alpha = g.point(lambda v: 255 - v)
bbox = alpha.point(lambda v: 255 if v > 20 else 0).getbbox()
print("lockup bbox", bbox)
def tint(mask, rgb):
    im = Image.new("RGBA", mask.size, rgb + (0,)); im.putalpha(mask); return im
lock = alpha.crop(bbox)
tint(lock, (255,255,255)).save(f"{out}/logo-white.png")
tint(lock, (10,19,36)).save(f"{out}/logo-firefly.png")
# mark = upper part above the wordmark: find the horizontal gap
w, h = lock.size
rows = [any(lock.getpixel((x, y)) > 20 for x in range(0, w, 2)) for y in range(h)]
gap = next(y for y in range(h//4, h) if not rows[y])
mark = lock.crop((0, 0, w, gap)); mark = mark.crop(mark.point(lambda v: 255 if v>20 else 0).getbbox())
print("mark size", mark.size)
tint(mark, (54,136,221)).save(f"{out}/mark-blue.png")
tint(mark, (255,255,255)).save(f"{out}/mark-white.png")
def square(m, size, bg=None, pad=0.12):
    side = int(max(m.size) * (1 + 2*pad)); c = Image.new("RGBA", (side, side), bg or (0,0,0,0))
    c.alpha_composite(m, ((side-m.size[0])//2, (side-m.size[1])//2)); return c.resize((size, size), Image.LANCZOS)
blue = tint(mark, (54,136,221))
square(blue, 512).save(f"{out}/icon-512.png")
square(blue, 32, pad=0.04).save(f"{out}/favicon-32.png")
square(tint(mark,(255,255,255)), 180, bg=(10,19,36,255), pad=0.18).save(f"{out}/apple-touch-icon.png")
square(blue, 48, pad=0.04).save(f"{out}/favicon.ico", sizes=[(16,16),(32,32),(48,48)])
Image.open(src_color).save(f"{out}/logo-color-small.png")
