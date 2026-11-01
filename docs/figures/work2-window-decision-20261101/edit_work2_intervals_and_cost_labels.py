from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work2-a-top-cleaned-markers.png"
OUTPUT = HERE / "work2-a-original-label-restored.png"

BLUE = (20, 55, 245, 255)
RED = (235, 40, 30, 255)
GREEN = (0, 245, 20, 255)
ORANGE = (224, 102, 0, 255)
WHITE = (255, 255, 255, 255)


def draw_interval(
    draw: ImageDraw.ImageDraw,
    x0: int,
    x1: int,
    y: int,
    label: str,
    color: tuple[int, int, int, int],
    font: ImageFont.FreeTypeFont,
) -> None:
    """画与真实端点对齐的区间线，而不是固定宽度的装饰符号。"""
    draw.line((x0, y, x1, y), fill=color, width=3)
    draw.line((x0, y - 5, x0, y + 5), fill=color, width=3)
    draw.line((x1, y - 5, x1, y + 5), fill=color, width=3)
    draw.text(((x0 + x1) // 2, 299), label, font=font, fill=color, anchor="mm")


def cubic_points(p0, p1, p2, p3, count: int = 16):
    points = []
    for i in range(count + 1):
        t = i / count
        u = 1 - t
        x = u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1]
        points.append((round(x), round(y)))
    return points


def draw_underbrace(
    draw: ImageDraw.ImageDraw,
    x0: int,
    x1: int,
    y: int,
    label: str,
    font: ImageFont.FreeTypeFont,
) -> None:
    """在公式项下方绘制横向大括号及成本含义。"""
    mid = (x0 + x1) // 2
    points = []
    points += cubic_points((x0, y), (x0 + 2, y), (x0 + 2, y + 7), (x0 + 11, y + 7))
    points += [(mid - 11, y + 7)]
    points += cubic_points((mid - 11, y + 7), (mid - 4, y + 7), (mid - 4, y + 15), (mid, y + 15))
    points += cubic_points((mid, y + 15), (mid + 4, y + 15), (mid + 4, y + 7), (mid + 11, y + 7))
    points += [(x1 - 11, y + 7)]
    points += cubic_points((x1 - 11, y + 7), (x1 - 2, y + 7), (x1 - 2, y), (x1, y))
    draw.line(points, fill=ORANGE, width=2, joint="curve")
    draw.text((mid, y + 30), label, font=font, fill=ORANGE, anchor="mm")


def main() -> None:
    image = Image.open(SOURCE).convert("RGBA")
    # 移动窗口时保留生图底稿原有字形、下标和抗锯齿，避免用另一个字体重写标签。
    window_label = image.crop((243, 208, 301, 229))
    draw = ImageDraw.Draw(image)
    chinese_font = ImageFont.truetype(r"C:\Windows\Fonts\simhei.ttf", 22)
    cost_font = ImageFont.truetype(r"C:\Windows\Fonts\simhei.ttf", 18)

    # 为早到示例腾出可读区间：将 e2 从 227 调到 260，原生图标签同步居中移动。
    draw.rectangle((218, 208, 324, 236), fill=WHITE)
    draw.line((260, 229, 321, 229), fill=GREEN, width=2)
    draw.line((260, 222, 260, 236), fill=GREEN, width=2)
    draw.line((321, 222, 321, 236), fill=GREEN, width=2)
    image.paste(window_label, (262, 208))

    # 上层状态区间：C2--e2 为早到，d6--C6 为延迟。
    draw.rectangle((180, 274, 270, 309), fill=WHITE)
    draw.rectangle((690, 274, 806, 309), fill=WHITE)
    draw_interval(draw, 217, 260, 279, "早到", BLUE, chinese_font)
    draw_interval(draw, 701, 795, 279, "延迟", RED, chinese_font)

    # EW3 是任务在 e3 之前完成的加工量，沿 J3 顶边突出 S3--e3 的蓝色段。
    draw.line((899, 700, 951, 700), fill=BLUE, width=5)
    draw.line((899, 697, 899, 704), fill=BLUE, width=3)
    draw.line((951, 697, 951, 704), fill=BLUE, width=3)

    # 窗口设置成本的两个加性分项分别说明其含义。
    draw_underbrace(draw, 1037, 1153, 470, "位置成本", cost_font)
    draw_underbrace(draw, 1184, 1334, 470, "宽度成本", cost_font)

    image.convert("RGB").save(OUTPUT, optimize=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
