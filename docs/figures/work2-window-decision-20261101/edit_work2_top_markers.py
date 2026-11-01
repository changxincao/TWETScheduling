from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work2-a-layout-et-corrected.png"
OUTPUT = HERE / "work2-a-top-cleaned-markers.png"


def is_cyan_projection(pixel: tuple[int, int, int, int]) -> bool:
    """只识别顶层调度图中的青色投影线，不触碰绿色窗口和黑色任务边界。"""
    red, green, blue, _ = pixel
    return blue >= 180 and green >= 145 and blue - red >= 18 and green - red >= 18


def remove_projection_line(
    image: Image.Image, x0: int, x1: int, y0: int, y1: int
) -> None:
    """擦除指定窄带内的青色像素；这些窄带均位于白色背景区域。"""
    pixels = image.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if is_cyan_projection(pixels[x, y]):
                pixels[x, y] = (255, 255, 255, 255)


def draw_status_marker(
    draw: ImageDraw.ImageDraw,
    center_x: int,
    label: str,
    color: tuple[int, int, int, int],
    font: ImageFont.FreeTypeFont,
) -> None:
    """沿用 Work1 的短横线加两端竖杠语法，作为早到/拖期状态标记。"""
    half_width = 16
    y = 279
    draw.line((center_x - half_width, y, center_x + half_width, y), fill=color, width=2)
    draw.line((center_x - half_width, y - 5, center_x - half_width, y + 5), fill=color, width=2)
    draw.line((center_x + half_width, y - 5, center_x + half_width, y + 5), fill=color, width=2)
    draw.text((center_x, 296), label, font=font, fill=color, anchor="mm")


def main() -> None:
    image = Image.open(SOURCE).convert("RGBA")

    # 这些竖线是生成图在顶层调度区留下的不一致投影；中间窗口决策图的约束虚线保留。
    projection_bands = [
        (130, 137, 54, 157),
        (379, 386, 45, 157),
        (583, 590, 45, 157),
        (189, 196, 184, 278),
        (252, 259, 184, 278),
        (317, 324, 184, 278),
        (352, 359, 184, 278),
    ]
    for band in projection_bands:
        remove_projection_line(image, *band)

    draw = ImageDraw.Draw(image)
    # 再覆盖各投影线处的浅色抗锯齿残影；区间端帽、任务边界和时间轴不在这些开放段内。
    for segment in [
        (128, 83, 140, 112),
        (376, 49, 390, 140),
        (580, 84, 594, 140),
        (186, 214, 200, 240),
        (249, 234, 263, 269),
        (314, 234, 328, 269),
        (349, 214, 363, 240),
    ]:
        draw.rectangle(segment, fill=(255, 255, 255, 255))

    # 清掉旧的纯文字状态标签，再按 Work1 的标记样式重画。
    draw.rectangle((198, 275, 260, 307), fill=(255, 255, 255, 255))
    draw.rectangle((704, 275, 771, 307), fill=(255, 255, 255, 255))
    font = ImageFont.truetype(r"C:\Windows\Fonts\simhei.ttf", 22)
    draw_status_marker(draw, 229, "早到", (20, 55, 245, 255), font)
    draw_status_marker(draw, 738, "拖期", (245, 35, 25, 255), font)

    image.convert("RGB").save(OUTPUT, optimize=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
