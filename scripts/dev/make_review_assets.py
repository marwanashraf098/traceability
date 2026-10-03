"""Review mode S5 — five simple, brand-free product illustrations (flat style), 800x800 WebP.

Drawn at 2x and downscaled (LANCZOS) for smooth edges. No text, no logos, no real products.
Output: classic-tee.webp, linen-shirt.webp, canvas-tote.webp, leather-wallet.webp, water-bottle.webp
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

S = 1600            # draw size (2x)
OUT = 800           # final size

def canvas(bg):
    img = Image.new("RGB", (S, S), bg)
    return img, ImageDraw.Draw(img)

def shadow(img, box, alpha=60):
    """Soft floor shadow under the product."""
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(box, fill=(0, 0, 0, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(28))
    img.paste(layer, (0, 0), layer)

def save(img, path):
    img.resize((OUT, OUT), Image.LANCZOS).save(path, "WEBP", quality=88, method=6)

def tee(path):
    img, d = canvas((232, 237, 244))
    shadow(img, (420, 1300, 1180, 1400))
    body = (63, 94, 140)
    d.polygon([(560, 360), (700, 330), (800, 400), (900, 330), (1040, 360),   # shoulders + neck
               (1290, 560), (1190, 720), (1080, 650),                            # right sleeve
               (1080, 1300), (520, 1300),                                        # hem
               (520, 650), (410, 720), (310, 560)], fill=body)                  # left sleeve
    d.ellipse((700, 300, 900, 440), fill=(232, 237, 244))                        # neckline
    d.arc((700, 300, 900, 440), 0, 180, fill=(48, 74, 112), width=22)
    d.line([(1080, 650), (1080, 1300)], fill=(54, 82, 124), width=8)            # side seams
    d.line([(520, 650), (520, 1300)], fill=(54, 82, 124), width=8)
    save(img, path)

def shirt(path):
    img, d = canvas((244, 239, 230))
    shadow(img, (420, 1320, 1180, 1420))
    body, dark = (214, 196, 160), (184, 164, 126)
    d.polygon([(580, 360), (800, 420), (1020, 360), (1280, 600), (1180, 760), (1080, 680),
               (1080, 1320), (520, 1320), (520, 680), (420, 760), (320, 600)], fill=body)
    d.polygon([(640, 330), (800, 430), (700, 520)], fill=dark)                  # collar points
    d.polygon([(960, 330), (800, 430), (900, 520)], fill=dark)
    d.line([(800, 430), (800, 1320)], fill=dark, width=10)                      # placket
    for y in range(520, 1300, 140):
        d.ellipse((782, y, 818, y + 36), fill=(250, 247, 240))                  # buttons
    d.rectangle((600, 780, 740, 900), outline=dark, width=8)                    # pocket
    save(img, path)

def tote(path):
    img, d = canvas((233, 238, 226))
    shadow(img, (380, 1330, 1220, 1430))
    body, dark = (112, 128, 84), (90, 104, 66)
    d.arc((500, 330, 740, 790), 180, 360, fill=dark, width=34)                  # handles (apart, no overlap)
    d.arc((860, 330, 1100, 790), 180, 360, fill=dark, width=34)
    d.polygon([(430, 560), (1170, 560), (1230, 1340), (370, 1340)], fill=body)  # bag
    d.rectangle((430, 560, 1170, 640), fill=dark)                               # top hem
    d.rounded_rectangle((640, 860, 960, 1100), radius=24, outline=(214, 220, 196), width=10)  # patch
    save(img, path)

def wallet(path):
    img, d = canvas((241, 233, 226))
    shadow(img, (360, 1120, 1240, 1220))
    body, dark, stitch = (124, 78, 48), (98, 60, 36), (214, 176, 132)
    d.rounded_rectangle((360, 520, 1240, 1140), radius=70, fill=body)
    d.rounded_rectangle((360, 520, 1240, 760), radius=70, fill=dark)            # flap
    d.rectangle((360, 700, 1240, 760), fill=dark)
    for x in range(420, 1190, 46):                                              # stitching
        d.line([(x, 1090), (x + 24, 1090)], fill=stitch, width=8)
        d.line([(x, 572), (x + 24, 572)], fill=stitch, width=8)
    d.ellipse((760, 700, 840, 780), fill=(196, 160, 112))                       # snap
    save(img, path)

def bottle(path):
    img, d = canvas((227, 240, 240))
    shadow(img, (560, 1380, 1040, 1460))
    body, dark, label = (44, 132, 138), (34, 108, 112), (236, 246, 246)
    d.rounded_rectangle((690, 240, 910, 380), radius=30, fill=(60, 66, 72))      # cap
    d.rectangle((720, 370, 880, 440), fill=dark)                                 # neck
    d.rounded_rectangle((590, 420, 1010, 1400), radius=120, fill=body)           # body
    d.rectangle((590, 760, 1010, 1000), fill=label)                              # band
    d.rectangle((640, 470, 700, 1340), fill=(70, 156, 162))                     # highlight
    save(img, path)

def main(outdir):
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in [("classic-tee", tee), ("linen-shirt", shirt), ("canvas-tote", tote),
                     ("leather-wallet", wallet), ("water-bottle", bottle)]:
        fn(out / f"{name}.webp")
        print(out / f"{name}.webp")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
