"""Remove the GIF's white matte without changing its motion or frame timing.

Requires Pillow. Originals live in static/images/interactions/source.
Re-run: python scripts/build-interaction-frames.py
"""
import json
import time
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'static/images/interactions'
SOURCES = {'like': 'bg_post_comment_like.gif', 'favorite': 'icon_post_detail_bottom_action_bar_collected.gif'}


def save_png(image, destination):
    # HBuilder watches static assets. Never expose a truncated/partial PNG to it.
    staging = destination.with_suffix('.png.tmp')
    if destination.exists():
        try:
            with Image.open(destination) as existing:
                unchanged = existing.mode == image.mode and existing.size == image.size and existing.tobytes() == image.tobytes()
            if unchanged:
                if staging.exists():
                    staging.unlink()
                return
        except OSError:
            pass  # Replace a corrupt/incomplete previous export.
    image.save(staging, format='PNG', optimize=True)
    for attempt in range(80):
        try:
            staging.replace(destination)
            return
        except PermissionError:
            if attempt == 79:
                raise
            time.sleep(.05)  # Windows readers may briefly deny replacement.


def remove_white_matte(frame):
    rgb = frame.convert('RGB')
    palette = sorted(rgb.getcolors(rgb.width * rgb.height), reverse=True)
    # Dominant stroke/fill colors distinguish opaque art from white-matted edges.
    candidates = [c for count, c in palette if min(c) < 230 and count >= max(10, rgb.width * rgb.height // 100)][:4]
    if not candidates:
        candidates = [c for _, c in palette if min(c) < 245][:1]
    distinct = []
    for color in candidates:
        basis = [255 - x for x in color]
        if any(sum(a*b for a,b in zip(basis,[255-x for x in other])) / (sum(a*a for a in basis)*sum((255-x)**2 for x in other))**.5 > .985 for other in distinct):
            continue  # Same white-matte ray: this is an edge, not another fill.
        distinct.append(color)
    candidates = distinct
    output = Image.new('RGBA', rgb.size)
    pixels = []
    for color in rgb.getdata():
        if min(color) >= 250:
            pixels.append((0, 0, 0, 0))
            continue
        best = None
        delta = [255 - x for x in color]
        for foreground in candidates:
            basis = [255 - x for x in foreground]
            alpha = max(0, min(1, sum(a*b for a, b in zip(delta, basis)) / sum(b*b for b in basis)))
            error = sum((a-alpha*b)**2 for a, b in zip(delta, basis))
            if best is None or error < best[0] - .01:
                best = error, alpha
        alpha = best[1] if best else 1
        if alpha < .02:
            pixels.append((0, 0, 0, 0))
        else:
            recovered = tuple(round(max(0, min(255, 255 - d/alpha))) for d in delta)
            pixels.append((*recovered, round(alpha * 255)))
    output.putdata(pixels)
    return output


OUT.mkdir(parents=True, exist_ok=True)
manifest = {}
styles = []
for kind, filename in SOURCES.items():
    with Image.open(OUT / 'source' / filename) as gif:
        frames, durations = [], []
        for index in range(gif.n_frames):
            gif.seek(index)  # Pillow composites disposal/partial GIF frames here.
            frames.append(remove_white_matte(gif.convert('RGBA')))
            durations.append(gif.info.get('duration', 40))
    width, height = frames[0].size
    strip = Image.new('RGBA', (width * len(frames), height))
    for index, frame in enumerate(frames):
        strip.paste(frame, (index * width, 0))
        assert frame.getpixel((0, 0))[3] == 0
    save_png(strip, OUT / f'{kind}-frames.png')
    save_png(frames[0], OUT / f'{kind}-idle.png')
    save_png(frames[-1], OUT / f'{kind}-active.png')
    total = sum(durations)
    assert total == 1440
    manifest[kind] = {'width': width, 'height': height, 'frames': len(frames), 'durations': durations, 'duration': total}
    elapsed = 0
    rules = []
    for index, duration in enumerate(durations):
        rules.append(f'{elapsed / total * 100:.8f}% {{ transform: translateX({-index / len(frames) * 100:.8f}%); }}')
        elapsed += duration
    rules.append(f'100% {{ transform: translateX({-(len(frames)-1) / len(frames) * 100:.8f}%); }}')
    styles.append(f'.interaction-frames.{kind} {{ width: {len(frames)*100}%; animation: interaction-{kind} {total}ms step-end 1 forwards; }}\n@keyframes interaction-{kind} {{\n'+ '\n'.join(rules) + '\n}')
def write_if_changed(path, text):
    if not path.exists() or path.read_text(encoding='utf-8') != text:
        staging = path.with_suffix(path.suffix + '.tmp')
        staging.write_text(text, encoding='utf-8')
        staging.replace(path)


write_if_changed(OUT / 'manifest.json', json.dumps(manifest, indent=2))
write_if_changed(ROOT / 'styles/interaction-frames.css', '\n'.join(styles) + '\n')
print(json.dumps(manifest))
