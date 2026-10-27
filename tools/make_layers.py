"""
Builds layered tvOS-style tiles for Glass Home.

For every app this writes, at 800x480 (5:3):
  <key>.l0.png  opaque back layer
  <key>.l1.png  transparent foreground layer (Settings also gets <key>.l2.png in front)
  <key>.png     flat composite, used where tiles are not animated (hero cards, fallbacks)

The art is kept flat: no baked shadows, glows or lighting on foreground layers. The launcher
renders parallax, the shadows between layers and the edge glare when a tile is focused.

Usage: python3 make_layers.py <icon-src dir> <assets/icons dir> <Inter variable font>
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

SRC, OUT, FONT = sys.argv[1], sys.argv[2], sys.argv[3]
W, H = 800, 480


def smoothstep(x, lo, hi):
    t = np.clip((x - lo) / (hi - lo), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def blur(arr, radius):
    """Gaussian blur of a float HxW or HxWx3 array in 0..1, via PIL."""
    if arr.ndim == 2:
        im = Image.fromarray(np.uint8(np.clip(arr, 0, 1) * 255), "L")
        return np.asarray(im.filter(ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255.0
    return np.stack([blur(arr[..., c], radius) for c in range(arr.shape[2])], axis=-1)


def fill_behind(img, alpha):
    """Background with the foreground removed: grow the surrounding colours inward (normalised convolution)."""
    keep = (1.0 - smoothstep(alpha, 0.02, 0.25)).astype(np.float32)
    fill = np.zeros_like(img)
    have = np.zeros(alpha.shape, dtype=bool)
    for r in (6, 14, 30, 60, 120):
        num = blur(img * keep[..., None], r)
        den = blur(keep, r)[..., None]
        est = num / np.maximum(den, 1e-4)
        ok = (den[..., 0] > 0.08) & ~have
        fill[ok] = est[ok]
        have |= ok
    fill[~have] = img[~have]
    # Away from the logo the real background is kept exactly
    w = smoothstep(alpha, 0.0, 0.05)[..., None]
    return img * (1 - w) + fill * w


def unpremultiply(img, bg, alpha):
    a = np.maximum(alpha, 1e-3)[..., None]
    return np.clip((img - (1 - a) * bg) / a, 0, 1)


def save_layers(key, l0, fgs):
    """l0: HxWx3 float; fgs: list of (rgb HxWx3, alpha HxW) from back to front."""
    Image.fromarray(np.uint8(np.clip(l0, 0, 1) * 255), "RGB").save(os.path.join(OUT, key + ".l0.png"), optimize=True)
    comp = l0.copy()
    for i, (rgb, a) in enumerate(fgs, start=1):
        rgba = np.dstack([np.clip(rgb, 0, 1), np.clip(a, 0, 1)])
        Image.fromarray(np.uint8(rgba * 255), "RGBA").save(os.path.join(OUT, f"{key}.l{i}.png"), optimize=True)
        comp = comp * (1 - a[..., None]) + rgb * a[..., None]
    Image.fromarray(np.uint8(np.clip(comp, 0, 1) * 255), "RGB").save(os.path.join(OUT, key + ".png"), optimize=True)
    for stale in range(len(fgs) + 1, 4):
        p = os.path.join(OUT, f"{key}.l{stale}.png")
        if os.path.exists(p):
            os.remove(p)
    print(f"{key}: {len(fgs) + 1} layers")


def load(name):
    im = Image.open(os.path.join(SRC, name)).convert("RGB")
    if im.size != (W, H):
        im = im.resize((W, H), Image.LANCZOS)
    return np.asarray(im, dtype=np.float32) / 255.0


def flat_logo(key, src):
    """Logo on a plain colour: the background is that colour, the logo is whatever differs from it."""
    img = load(src)
    border = np.concatenate([img[:6].reshape(-1, 3), img[-6:].reshape(-1, 3), img[:, :6].reshape(-1, 3), img[:, -6:].reshape(-1, 3)])
    bg = np.median(border, axis=0)
    dist = np.sqrt(((img - bg) ** 2).sum(-1)) * 255
    alpha = smoothstep(dist, 14, 70)
    l0 = np.broadcast_to(bg, img.shape).copy()
    save_layers(key, l0, [(unpremultiply(img, l0, alpha), alpha)])


def light_logo(key, src, lo=0.45, hi=0.80):
    """Light logo on a gradient: find the near-white logo, fill the gradient in behind a slightly wider
    area (so no ghost remains), then take as foreground everything that differs from that fill,
    which keeps the logo's own soft glow on the foreground layer."""
    img = load(src)
    core = smoothstep(img.min(axis=-1), lo, hi)
    region = np.clip(blur((core > 0.02).astype(np.float32), 6) * 12, 0, 1)
    l0 = fill_behind(img, region)
    dist = np.sqrt(((img - l0) ** 2).sum(-1)) * 255
    alpha = smoothstep(dist, 6, 48) * region
    save_layers(key, l0, [(unpremultiply(img, l0, alpha), alpha)])


def netflix():
    """Flat near-black, no gradient; the red wordmark alone on its layer, with no glow baked in."""
    img = load("netflix-white.png")
    alpha = np.clip((0.92 - img[..., 1]) / (0.92 - 0.05), 0, 1)
    # The wordmark at 80% of the tile's width, about its centre, as on tvOS: black all round it
    ys, xs = np.nonzero(alpha > 0.5)
    gx, gy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    scale = 0.80 * W / (xs.max() - xs.min())
    small = Image.fromarray(np.uint8(alpha * 255), "L").resize((round(W * scale), round(H * scale)), Image.LANCZOS)
    canvas = Image.new("L", (W, H), 0)
    canvas.paste(small, (round(W / 2 - gx * scale), round(H / 2 - gy * scale)))
    alpha = np.asarray(canvas, dtype=np.float32) / 255.0
    red = np.array([226, 9, 20], dtype=np.float32) / 255
    l0 = np.full(img.shape, 15 / 255, dtype=np.float32)
    save_layers("com.netflix.ninja", l0, [(np.broadcast_to(red, img.shape).copy(), alpha)])


def apple_tv():
    """Black, with the Apple logo and "tv" in white fading to soft pastel colour at the lower edges."""
    img = load("apple-tv.png")
    glyphs = smoothstep(img.min(axis=-1), 0.42, 0.70)
    # Larger, like the reference: scale the glyphs about their centre to ~48% of the tile width
    ys, xs = np.nonzero(glyphs > 0.5)
    gx, gy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    scale = 0.48 * W / (xs.max() - xs.min())
    big = Image.fromarray(np.uint8(glyphs * 255), "L").resize((round(W * scale), round(H * scale)), Image.LANCZOS)
    canvas = Image.new("L", (W, H), 0)
    canvas.paste(big, (round(W / 2 - gx * scale), round(H / 2 - gy * scale)))
    alpha = np.asarray(canvas, dtype=np.float32) / 255.0
    ys, xs = np.nonzero(alpha > 0.5)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    u = np.clip((xx - x0) / (x1 - x0), 0, 1)
    v = np.clip((yy - y0) / (y1 - y0), 0, 1)
    # Pastel tint along the width: violet, periwinkle, mint, lime, peach, rose
    stops = [(0.00, (186, 140, 252)), (0.24, (150, 172, 252)), (0.46, (132, 222, 196)),
             (0.60, (204, 230, 120)), (0.80, (250, 192, 150)), (1.00, (246, 168, 196))]
    tint = np.zeros(img.shape, dtype=np.float32)
    for c in range(3):
        tint[..., c] = np.interp(u, [s[0] for s in stops], [s[1][c] / 255 for s in stops])
    # Colour lives near the glyph edges and toward the bottom; the cores stay white
    inside = blur(alpha, 14)
    edge = np.clip((1 - inside) * 2.6, 0, 1)
    weight = np.clip(edge * (0.30 + 0.70 * smoothstep(v, 0.25, 1.0)) * 1.05, 0, 0.95)
    white = np.array([0.965, 0.965, 0.975], dtype=np.float32)
    rgb = white * (1 - weight[..., None]) + tint * weight[..., None]
    l0 = np.full(img.shape, 4 / 255, dtype=np.float32)
    save_layers("com.apple.atve.amazon.appletv", l0, [(rgb, alpha)])


def settings():
    """Same treatment as Netflix: flat near-black; the inner gear and the outer gear float on their own layers."""
    S = 3
    w, h = W * S, H * S
    cx, cy = w / 2, h / 2

    def toothed(r_root, r_tip, n, root_frac=0.56, tip_frac=0.34, phase=0.0):
        pts, step = [], 2 * math.pi / n
        for i in range(n):
            a = i * step + phase
            for da, r in [(-step * root_frac / 2, r_root), (-step * tip_frac / 2, r_tip), (step * tip_frac / 2, r_tip), (step * root_frac / 2, r_root)]:
                pts.append((cx + r * math.cos(a + da), cy + r * math.sin(a + da)))
        return pts

    def disk(r):
        m = Image.new("L", (w, h), 0)
        ImageDraw.Draw(m).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
        return m

    def poly(pts):
        m = Image.new("L", (w, h), 0)
        ImageDraw.Draw(m).polygon(pts, fill=255)
        return m

    def thr(m, t=128):
        return m.point(lambda p: 255 if p >= t else 0)

    k = S
    R_tip, R_root, R_band = 168 * k, 150 * k, 127 * k
    r_tip, r_root, r_hole = 117 * k, 102 * k, 83 * k
    spoke_w, hub_r, pin_r = 17 * k, 16 * k, 6.5 * k
    band = ImageChops.subtract(disk(R_root + 2 * k), disk(R_band))
    spokes = Image.new("L", (w, h), 0)
    sd = ImageDraw.Draw(spokes)
    for deg in (0, 120, 240):
        a = math.radians(deg - 2)
        dx, dy = math.cos(a), math.sin(a)
        nx, ny = -dy, dx
        L, hw = R_band + 6 * k, spoke_w / 2
        sd.polygon([(cx + nx * hw, cy + ny * hw), (cx + dx * L + nx * hw, cy + dy * L + ny * hw),
                    (cx + dx * L - nx * hw, cy + dy * L - ny * hw), (cx - nx * hw, cy - ny * hw)], fill=255)
    sd.ellipse([cx - hub_r, cy - hub_r, cx + hub_r, cy + hub_r], fill=255)
    frame = ImageChops.lighter(band, spokes)
    grow = frame.filter(ImageFilter.GaussianBlur(11 * k)).point(lambda p: 255 if p > 18 else 0)
    closed = grow.filter(ImageFilter.GaussianBlur(11 * k)).point(lambda p: 255 if p > 237 else 0)
    frame = ImageChops.multiply(ImageChops.lighter(frame, closed), disk(R_root + 2 * k))
    outer = ImageChops.lighter(frame, ImageChops.subtract(poly(toothed(R_root, R_tip, 48)), disk(R_root - 1 * k)))
    outer = ImageChops.subtract(outer, disk(pin_r))
    inner = ImageChops.subtract(poly(toothed(r_root, r_tip, 40, phase=math.pi / 40)), disk(r_hole))
    inner = ImageChops.subtract(inner, outer)

    def small(m):
        return np.asarray(m.resize((W, H), Image.LANCZOS), dtype=np.float32) / 255.0

    a_inner, a_outer = small(inner), small(outer)
    l0 = np.full((H, W, 3), 15 / 255, dtype=np.float32)
    silver_dark = np.full((H, W, 3), [148 / 255, 152 / 255, 158 / 255], dtype=np.float32)
    silver = np.full((H, W, 3), [208 / 255, 211 / 255, 216 / 255], dtype=np.float32)
    save_layers("settings", l0, [(silver_dark, a_inner), (silver, a_outer)])


def fireplayer():
    """Blue/black back layer with its light strip; the play triangle and wordmark float in front, flat."""
    S = 2
    w, h = W * S, H * S
    yy = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    top, bottom = np.array([10, 26, 62]) / 255, np.array([2, 4, 12]) / 255
    bg = np.broadcast_to(top + (bottom - top) * yy[..., None], (h, w, 3)).astype(np.float32).copy()
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse([w * 0.12, h * 0.80, w * 0.88, h * 1.10], fill=255)
    g = np.asarray(glow.filter(ImageFilter.GaussianBlur(70 * S)), dtype=np.float32)[..., None] / 255 * 0.85
    bg = bg * (1 - g) + np.array([30, 110, 255]) / 255 * g
    strip = Image.new("L", (w, h), 0)
    ImageDraw.Draw(strip).rectangle([w * 0.2, h * 0.955, w * 0.8, h * 0.975], fill=255)
    s = np.asarray(strip.filter(ImageFilter.GaussianBlur(6 * S)), dtype=np.float32)[..., None] / 255
    bg = bg * (1 - s) + np.array([140, 200, 255]) / 255 * s
    cx, cy, r = w * 0.5, h * 0.40, h * 0.22
    tri = Image.new("L", (w, h), 0)
    ImageDraw.Draw(tri).polygon([(cx - r * 0.62, cy - r), (cx - r * 0.62, cy + r), (cx + r * 0.95, cy)], fill=255)
    tri = tri.filter(ImageFilter.GaussianBlur(10 * S)).point(lambda p: 255 if p > 128 else 0).filter(ImageFilter.GaussianBlur(1.2 * S))
    word = Image.new("L", (w, h), 0)
    f = ImageFont.truetype(FONT, 46 * S)
    try:
        f.set_variation_by_axes([32, 620])
    except Exception:
        pass
    d = ImageDraw.Draw(word)
    tw = d.textlength("FirePlayer", font=f)
    d.text((cx - tw / 2, h * 0.735), "FirePlayer", font=f, fill=255)

    def small(m):
        return np.asarray(m.resize((W, H), Image.LANCZOS), dtype=np.float32) / 255.0

    a_tri, a_word = small(tri) * 0.94, small(word)
    rgb = np.zeros((H, W, 3), dtype=np.float32)
    rgb[:] = np.array([126, 178, 255]) / 255
    wmask = a_word > a_tri
    rgb[wmask] = 1.0
    alpha = np.maximum(a_tri, a_word)
    l0 = np.asarray(Image.fromarray(np.uint8(np.clip(bg, 0, 1) * 255)).resize((W, H), Image.LANCZOS), dtype=np.float32) / 255
    save_layers("app.fireplayer", l0, [(rgb, alpha)])


netflix()
apple_tv()
settings()
fireplayer()
for key in ("com.amazon.firetv.youtube", "com.hbo.hbonow", "ar.tvplayer.tv", "com.amazon.firebat",
            "com.spotify.tv.android", "com.esaba.downloader", "com.tailscale.ipn"):
    flat_logo(key, key + ".png")
for key in ("com.disney.disneyplus", "gamepass.fire.tv", "com.amazon.venezia", "com.apple.android.music", "com.amazon.tv.livetv"):
    light_logo(key, key + ".png")
