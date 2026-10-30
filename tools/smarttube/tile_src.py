# Source picture for Glass Home's SmartTube tile: YouTube's tile on black, the play button in
# exactly YouTube's shape but blue, and the SmartTube wordmark (wordmark.py) in white.
# Usage: tile_src.py <youtube src png> <wordmark png> <blue hex> <out png>
import sys, numpy as np
from PIL import Image
from scipy.ndimage import binary_fill_holes
yt_path, wm_path, blue_hex, out_path = sys.argv[1:5]
src = np.asarray(Image.open(yt_path).convert('RGB')).astype(np.float32) / 255
H, W = src.shape[:2]
blue = np.array([int(blue_hex[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255
grey = src.mean(2)
# The button: pure red on white, so green measures how much white each pixel has
core = binary_fill_holes(src[..., 1] < 0.5); core[:, 295:] = False
alpha = np.where(core, 1.0, np.clip(1 - src[..., 1], 0, 1)); alpha[:, 295:] = 0; alpha[alpha < 0.04] = 0
white = np.clip(src[..., 1], 0, 1)[..., None]                # inside: the triangle's white, blended at its edges
rgb = np.where(core[..., None], blue * (1 - white) + white, blue)
ys, xs = np.where(alpha > 0)
bb = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
button = np.dstack([rgb, alpha])[bb[1]:bb[3], bb[0]:bb[2]]
# YouTube's layout: wordmark box 302..722 x 168..302, a 17 px gap after the button
wm = np.asarray(Image.open(wm_path)).astype(np.float32) / 255
gap = 302 - bb[2]
total = button.shape[1] + gap + wm.shape[1]
k = min(1.0, (722 - bb[0] + 1) / total)                      # within YouTube's logo width
def scaled(a, mode):
    im = Image.fromarray(np.uint8(np.clip(a, 0, 1) * 255), mode)
    return np.asarray(im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS)).astype(np.float32) / 255
bt = scaled(button, 'RGBA'); wmk = scaled(wm, 'L')
out = np.zeros((H, W, 3), np.float32)
def paste(c, a, x, y):
    h, w = a.shape
    out[y:y + h, x:x + w] = out[y:y + h, x:x + w] * (1 - a[..., None]) + c * a[..., None]
tw = bt.shape[1] + round(gap * k) + wmk.shape[1]
x0 = (W - tw) // 2
cy = (bb[1] + bb[3]) / 2
paste(bt[..., :3], bt[..., 3], x0, int(round(cy - bt.shape[0] / 2)))
wy = int(round(cy + (168 - cy) * k))                          # the wordmark's top, as YouTube's, about the centre
paste(np.ones(wmk.shape + (3,), np.float32), wmk, x0 + bt.shape[1] + round(gap * k), wy)
Image.fromarray(np.uint8(np.clip(out, 0, 1) * 255), 'RGB').save(out_path)
