# "SmartTube" in white for Glass Home's SmartTube tile: Fjalla One (OFL) in its own proportions,
# every letter the same visible distance from the next (ink to ink), a little tighter than the
# font sets them, scaled to the height of YouTube's wordmark (measured from YouTube's tile).
# Writes an alpha PNG. Usage: wordmark.py <youtube src png> <Fjalla One ttf> <out png> [gap, of the cap height]
import sys, numpy as np
from PIL import Image, ImageDraw, ImageFont
yt_path, font_path, out_path = sys.argv[1:4]
GAP = float(sys.argv[4]) if len(sys.argv) > 4 else 0.045
g = np.asarray(Image.open(yt_path).convert('L')).astype(np.float32) / 255
ink = (1 - g[168:303, 302:723]) > 0.5
rows = np.where(ink.any(axis=1))[0]
target_h = rows.max() - rows.min() + 1                 # YouTube's wordmark, top of the T/b to the baseline
size = 600
f = ImageFont.truetype(font_path, size)
asc, desc = f.getmetrics()
glyphs = []
for c in 'SmartTube':
    im = Image.new('L', (size * 2, size * 2), 0)
    ImageDraw.Draw(im).text((size // 2, 0), c, font=f, fill=255)
    a = np.asarray(im).astype(np.float32) / 255
    cols = np.where(a.max(axis=0) > 0.02)[0]
    glyphs.append(a[:, cols.min():cols.max() + 1])     # ink width only, its height on the shared baseline
cap = None
t_rows = np.where(glyphs[5].max(axis=1) > 0.5)[0]      # the T
cap = t_rows.max() - t_rows.min() + 1
gap = GAP * cap
width = int(sum(gl.shape[1] for gl in glyphs) + gap * (len(glyphs) - 1)) + 2
out = np.zeros((glyphs[0].shape[0], width), np.float32)
x = 0.0
for gl in glyphs:
    xi = int(round(x))
    out[:, xi:xi + gl.shape[1]] = np.maximum(out[:, xi:xi + gl.shape[1]], gl)
    x += gl.shape[1] + gap
rs = np.where(out.max(axis=1) > 0.02)[0]; cs = np.where(out.max(axis=0) > 0.02)[0]
out = out[rs.min():rs.max() + 1, cs.min():cs.max() + 1]
im = Image.fromarray(np.uint8(out * 255), 'L')
k = target_h / im.height
im = im.resize((round(im.width * k), target_h), Image.LANCZOS)
im.save(out_path)
print('wordmark', im.width, 'x', im.height, 'gap', round(gap * k, 1), 'px')
