# Source picture for Glass Home's SmartTube tile (icon-src/org.smarttube.stable.png), made once: YouTube's tile with a black background, the same
# red play button, and "SmartTube" in white, set as YouTube's wordmark is (Roboto Condensed Black,
# tightly spaced, the wordmark's own height; the font is Google's Roboto Condensed, OFL, used only
# to draw this picture). Usage: make_smarttube_src.py <youtube src png> <font> <out png> <compare png>
import sys, numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import binary_fill_holes
yt_path, font_path, out_path, cmp_path = sys.argv[1:5]
yt = Image.open(yt_path).convert('RGB')
src = np.asarray(yt).astype(np.float32) / 255
W, H = yt.size
grey = src.mean(2)
TRACK = -0.04   # em, the wordmark's tight spacing

def wordmark(text, size):
    """The text in white on transparent, tightly tracked; returns (alpha array, baseline y in it)."""
    f = ImageFont.truetype(font_path, size)
    f.set_variation_by_axes([900])
    pad = size
    width = int(sum(f.getlength(c) for c in text) + pad * 2)
    im = Image.new('L', (width, size * 2), 0)
    d = ImageDraw.Draw(im)
    x = pad
    for c in text:
        d.text((x, pad // 2), c, font=f, fill=255)
        x += f.getlength(c) + TRACK * size
    a = np.asarray(im).astype(np.float32) / 255
    ys, xs = np.where(a > 0.5)
    return a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]

# The real wordmark's box (dark pixels right of the button)
dark = grey < 0.5
dark[:, :300] = False
ys, xs = np.where(dark)
box = (xs.min(), ys.min(), xs.max(), ys.max())
target_h = box[3] - box[1] + 1
# Roboto Condensed is still wider than YouTube's lettering: "YouTube" set our way, then narrowed
# and scaled to the real wordmark's box; "SmartTube" gets the same narrowing and scale
def squeeze(a, kx, ky):
    im = Image.fromarray(np.uint8(a * 255), 'L')
    return np.asarray(im.resize((max(1, round(a.shape[1] * kx)), max(1, round(a.shape[0] * ky))), Image.LANCZOS)).astype(np.float32) / 255
probe = wordmark('YouTube', 400)
kx, ky = (box[2] - box[0] + 1) / probe.shape[1], target_h / probe.shape[0]
yt_ours = squeeze(probe, kx, ky)
st = squeeze(wordmark('SmartTube', 400), kx, ky)
print('YouTube real', box[2] - box[0] + 1, 'x', target_h, '| ours', yt_ours.shape[1], 'x', yt_ours.shape[0], '| SmartTube', st.shape[1], 'x', st.shape[0])

# Alpha from the green channel: the button is pure red (no green) on white, so at its rim
# alpha = 1 - green exactly; inside the red shape (the white triangle too) it is solid
red_core = binary_fill_holes(src[..., 1] < 0.5)
red_core[:, 295:] = False
alpha = np.where(red_core, 1.0, np.clip(1 - src[..., 1], 0, 1))
alpha[:, 295:] = 0
alpha[alpha < 0.04] = 0
solid = alpha > 0
red = np.median(src[(src[..., 1] < 0.1) & (src[..., 0] > 0.8)], axis=0)
# The rim takes the button's own red (no white mixed in); inside, the original pixels
button_rgb = np.where(red_core[..., None], src, red)
bys, bxs = np.where(solid)
bb = (bxs.min(), bys.min(), bxs.max(), bys.max())

# Lay out: button, the real gap, then the white wordmark; centred, scaled to the logo's width
gap = box[0] - bb[2]
bw, bh = bb[2] - bb[0] + 1, bb[3] - bb[1] + 1
total = bw + gap + st.shape[1]
max_w = (box[2] - bb[0] + 1)          # YouTube's logo width
k = min(1.0, max_w / total)
out = np.zeros((H, W, 3), np.float32)
def paste(rgb, a, x, y):
    h, w = a.shape
    region = out[y:y + h, x:x + w]
    region[:] = region * (1 - a[..., None]) + rgb * a[..., None]
bt = Image.fromarray(np.uint8(np.dstack([button_rgb, alpha])[bb[1]:bb[3] + 1, bb[0]:bb[2] + 1] * 255), 'RGBA')
bt = bt.resize((round(bw * k), round(bh * k)), Image.LANCZOS)
wm = Image.fromarray(np.uint8(st * 255), 'L').resize((round(st.shape[1] * k), round(st.shape[0] * k)), Image.LANCZOS)
tw = bt.width + round(gap * k) + wm.width
x0 = (W - tw) // 2
cy = (bb[1] + bb[3]) / 2                      # the button's centre, as YouTube's
btn = np.asarray(bt).astype(np.float32) / 255
paste(btn[..., :3], btn[..., 3], x0, int(round(cy - bt.height / 2)))
# The wordmark keeps its place relative to the button: its top/bottom as in YouTube's, scaled about the centre
wy = int(round(cy + (box[1] - cy) * k))
wma = np.asarray(wm).astype(np.float32) / 255
paste(np.ones(wma.shape + (3,), np.float32), wma, x0 + bt.width + round(gap * k), wy)
Image.fromarray(np.uint8(np.clip(out, 0, 1) * 255), 'RGB').save(out_path)

# Comparison: the real wordmark over ours
c = Image.new('RGB', (900, 2 * (target_h + 30)), 'white')
c.paste(yt.crop((box[0] - 5, box[1] - 5, box[2] + 5, box[3] + 5)), (10, 10))
ours = Image.fromarray(np.uint8((1 - yt_ours) * 255), 'L').convert('RGB')
c.paste(ours, (10, target_h + 40))
c.save(cmp_path)
