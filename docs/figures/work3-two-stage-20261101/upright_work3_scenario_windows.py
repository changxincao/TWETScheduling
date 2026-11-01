"""仅将三个场景的窗口标签改为正体，保留已有几何修订。"""

import json
from hashlib import sha256
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work3-annotations-corrected.png"
OUTPUT = HERE / "work3-scenario-windows-upright.png"


def main():
    """主字与下标都显式使用 Regular，避免再次落到 timesi.ttf。"""
    image = Image.open(SOURCE).convert("RGB")
    before = np.asarray(image).copy()
    scale = 4
    main_font = ImageFont.truetype(r"C:\Windows\Fonts\times.ttf", 19 * scale)
    sub_font = ImageFont.truetype(r"C:\Windows\Fonts\times.ttf", 12 * scale)
    assert main_font.getname()[1] == sub_font.getname()[1] == "Regular"
    allowed = np.zeros(before.shape[:2], dtype=bool)
    boxes = []
    for center in [238, 671, 1103]:
        box = (center - 29, 713, center + 30, 733)
        patch = Image.new("RGB", (59 * scale, 20 * scale), (222, 253, 236))
        draw = ImageDraw.Draw(patch)
        chunks = [("[e", main_font, 727), ("3", sub_font, 730),
                  (",d", main_font, 727), ("3", sub_font, 730), ("]", main_font, 727)]
        widths = [draw.textlength(text, font=face) for text, face, _ in chunks]
        x = (patch.width - sum(widths)) / 2
        for (text, face, baseline), width in zip(chunks, widths):
            draw.text((round(x), (baseline - box[1]) * scale), text,
                      font=face, fill=(0, 195, 0), anchor="ls")
            x += width
        image.paste(patch.resize((59, 20), Image.Resampling.LANCZOS), box[:2])
        allowed[box[1]:box[3], box[0]:box[2]] = True
        boxes.append(box)
    changed = np.any(before != np.asarray(image), axis=2)
    assert not np.any(changed & ~allowed), "三处标签之外发生变化"
    image.save(OUTPUT, optimize=True)
    audit = dict(source=SOURCE.name, output=OUTPUT.name, size=image.size,
                 source_sha256=sha256(SOURCE.read_bytes()).hexdigest(),
                 output_sha256=sha256(OUTPUT.read_bytes()).hexdigest(),
                 font_file=r"C:\Windows\Fonts\times.ttf", font_family=main_font.getname()[0],
                 main_style=main_font.getname()[1], subscript_style=sub_font.getname()[1],
                 main_font_pixels=19, subscript_font_pixels=12, label_boxes=boxes,
                 unexpected_changed_pixels=int(np.count_nonzero(changed & ~allowed)))
    (HERE / "scenario-windows-upright-check.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
