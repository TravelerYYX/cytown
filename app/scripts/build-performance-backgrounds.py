"""Rebuild static background effects; requires Pillow. Sources remain untouched."""
from pathlib import Path
from PIL import Image, ImageEnhance, ImageFilter

root = Path(__file__).resolve().parents[1]
output = root / 'static/images/performance'
output.mkdir(parents=True, exist_ok=True)
home = Image.open(root / 'static/images/home-banner.jpg').convert('RGB')
# Existing banner: 28rpx blur on an image overscanned to 894rpx width.
home.filter(ImageFilter.GaussianBlur(home.width * 28 / 894)).save(output / 'home-banner-blurred.jpg', quality=92, optimize=True)
profile = Image.open(root / 'static/images/assets/my_background.jpg').convert('RGB')
profile = ImageEnhance.Color(ImageEnhance.Brightness(profile).enhance(1.35)).enhance(1.1)
profile.save(output / 'profile-background.jpg', quality=92, optimize=True)
profile.filter(ImageFilter.GaussianBlur(profile.width * 14 / 390)).save(output / 'profile-blurred.jpg', quality=92, optimize=True)
print('Static performance backgrounds rebuilt.')
