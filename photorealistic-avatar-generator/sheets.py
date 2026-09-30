"""Review contact sheets: sheets.py SEX FIRST LAST -> work/sheets/SEX-FIRST-LAST.jpg"""
import json
import os
import sys

from PIL import Image, ImageDraw

from pool import MANIFEST_PATH, OUT_DIR

HERE = os.path.dirname(os.path.abspath(__file__))
sex, first, last = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
manifest = json.load(open(MANIFEST_PATH))["images"]
cell, label_h, pad, cols = 220, 30, 10, 5
names = [f"{sex}-{n}" for n in range(first, last + 1) if os.path.exists(os.path.join(OUT_DIR, f"{sex}-{n}.jpg"))]
rows = (len(names) + cols - 1) // cols
sheet = Image.new("RGB", (cols * (cell + pad) + pad, rows * (cell + label_h + pad) + pad), "white")
draw = ImageDraw.Draw(sheet)
for i, name in enumerate(names):
    x, y = pad + (i % cols) * (cell + pad), pad + (i // cols) * (cell + label_h + pad)
    sheet.paste(Image.open(os.path.join(OUT_DIR, f"{name}.jpg")).resize((cell, cell), Image.LANCZOS), (x, y))
    m = manifest[name]
    draw.text((x, y + cell + 2), f"{name} {m['age']} {m['ethnicity'].replace('white ', '')}", fill="black")
    hair = m["prompt"].split(" with ", 1)[1].split(", wearing")[0]
    draw.text((x, y + cell + 15), hair[:34], fill="gray")
os.makedirs(os.path.join(HERE, "work", "sheets"), exist_ok=True)
out = os.path.join(HERE, "work", "sheets", f"{sex}-{first}-{last}.jpg")
sheet.save(out, quality=88)
print(out, len(names))
