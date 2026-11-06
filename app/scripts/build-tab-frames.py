"""Pack the original tab GIF frames for deterministic, single-play CSS animation."""
from pathlib import Path

from PIL import Image

images = Path(__file__).resolve().parents[1] / "static" / "images"
for source in sorted(images.glob("icon_home_rb_btn_*_selected_animated.gif")):
    with Image.open(source) as animation:
        assert animation.n_frames == 15 and animation.size == (90, 90), source
        strip = Image.new("RGBA", (90 * animation.n_frames, 90))
        for index in range(animation.n_frames):
            animation.seek(index)
            assert animation.info["duration"] == 40, source
            strip.paste(animation.convert("RGBA"), (90 * index, 0))
        output = source.with_name(source.stem.replace("_animated", "_frames") + ".png")
        strip.save(output, optimize=True)
        print(output.name)
