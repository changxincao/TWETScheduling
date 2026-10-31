"""继续编辑已授权的生图 PNG：统一分组、搬移定义，并加入非零提前／拖期示例。"""
from pathlib import Path
import json

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "docs/figures/work1-final-20261031/work1-corrected-4k.png"
OUT = ROOT / "docs/figures/work1-layout-20261031"
OUT.mkdir(parents=True, exist_ok=True)
S = 3
src = Image.open(BASE).convert("RGB")
assert src.size == (4344, 3258)
canvas = src.copy()
draw = ImageDraw.Draw(canvas)
BLACK, BLUE, RED, GREEN = (0, 0, 0), (0, 25, 245), (245, 0, 0), (0, 155, 52)
FRAME, BAND, GUIDE = (185, 214, 240), (229, 255, 241), (72, 205, 240)
E, D, P = 407, 607, 100
song = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 26*S)
small_song = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 16*S)
italic = ImageFont.truetype("C:/Windows/Fonts/timesi.ttf", 17*S)


def bounds(rect):
    return tuple(round(v*S) for v in rect)


def box(rect, fill="white", outline=None, width=1):
    draw.rectangle(bounds(rect), fill=fill, outline=outline, width=max(1, round(width*S)))


def line(points, color=BLACK, width=1):
    draw.line([(round(x*S), round(y*S)) for x, y in points], fill=color, width=max(1, round(width*S)))


def ink(rect, color=BLACK):
    """搬移原图的墨迹字形，不携带原来的背景、竖线或相邻色块。"""
    a = np.asarray(src.crop(bounds(rect))).astype(float)
    if color == BLACK:
        alpha = 255-a.max(axis=2)
    else:
        alpha = 255-a.min(axis=2)
        if color == RED:
            alpha[(a[:, :, 0]-np.maximum(a[:, :, 1], a[:, :, 2])) < 30] = 0
        elif color == BLUE:
            alpha[(a[:, :, 2]-np.maximum(a[:, :, 0], a[:, :, 1])) < 30] = 0
    alpha[alpha < 35] = 0
    patch = Image.new("RGBA", (a.shape[1], a.shape[0]), color+(255,))
    patch.putalpha(Image.fromarray(alpha.astype("uint8")))
    crop = patch.getbbox()
    assert crop, rect
    return patch.crop(crop)


def paste(patch, x, y, center=False):
    if center:
        x -= patch.width/(2*S)
    canvas.paste(patch, (round(x*S), round(y*S)), patch if patch.mode == "RGBA" else None)


def crop_to(rect, x, y):
    paste(src.crop(bounds(rect)), x, y)


def dashed(x, lo, hi):
    for y in range(round(lo), round(hi), 9):
        line([(x, y), (x, min(y+5, hi))], GUIDE, .8)


def axis(x0, x1, y):
    line([(x0, y), (x1-5, y)], width=1.2)
    draw.polygon([(round(x*S), round(z*S)) for x, z in
                  [(x1, y), (x1-9, y-4), (x1-9, y+4)]], fill=BLACK)


def dot(x, y, radius=3.7):
    draw.ellipse(bounds((x-radius, y-radius, x+radius, y+radius)), fill=BLACK)


def title_text(text, x, y):
    draw.text((round(x*S), round(y*S)), text, font=song, fill=BLACK,
              anchor="mt", stroke_width=1)


def frame(rect, title=None, title_patch=None, fill=False):
    x0, y0, x1, y1 = rect
    draw.rounded_rectangle(bounds(rect), radius=4*S, fill="white" if fill else None,
                           outline=FRAME, width=S)
    # 沿用模型原稿的浅蓝纵向色带，不加深标题颜色。
    strip = src.crop(bounds((25, 7, 26, 50))).resize((round((x1-x0-2)*S), 43*S))
    paste(strip, x0+1, y0+1)
    if title_patch is not None:
        paste(title_patch, (x0+x1)/2, y0+10, True)
    elif title:
        title_text(title, (x0+x1)/2, y0+8)
    draw.rounded_rectangle(bounds(rect), radius=4*S, outline=FRAME, width=S)


window_labels = [ink(r, GREEN) for r in [(244, 61, 318, 83), (234, 174, 305, 196),
                                         (475, 61, 545, 83), (520, 174, 591, 196),
                                         (744, 61, 818, 83), (665, 174, 736, 196)]]
c_labels = [ink(r) for r in [(285, 92, 318, 114), (272, 201, 305, 222),
                             (552, 92, 584, 114), (556, 201, 587, 222),
                             (801, 92, 834, 114), (703, 201, 735, 222)]]
job_rects = [(150, 113, 280, 145), (145, 221, 267, 253), (447, 113, 547, 145),
             (406, 221, 551, 253), (674, 113, 796, 145), (551, 221, 698, 253)]
job_patches = [src.crop(bounds((x0, y0, x1+1/S, y1+1/S))) for x0, y0, x1, y1 in job_rects]
s3 = ink((166, 425, 196, 450))
e3 = ink((394, 427, 424, 449))
d3 = ink((594, 427, 624, 449))
t3 = ink((620, 528, 652, 552), RED)
time_label = ink((881, 153, 923, 178))


def window(lo, hi, y, label):
    line([(lo, y), (hi, y)], GREEN, 1.5)
    for x in [lo, hi]:
        line([(x, y-5), (x, y+5)], GREEN, 1.2)
    paste(label, (lo+hi)/2, y-24, True)


def job(j, lo, y):
    patch = job_patches[j-1]
    width = (patch.width-1)/S
    paste(patch, lo, y-32)
    dot(lo+width, y-32)
    paste(c_labels[j-1], lo+width+6, y-50)
    return lo+width


def idle(lo, hi, y):
    for x in range(lo+6, hi-5, 7):
        line([(x, y), (min(x+4, hi-5), y)], (90, 90, 90), .8)
    for x, d in [(lo, 1), (hi, -1)]:
        draw.polygon([tuple(round(v*S) for v in p) for p in
                      [(x, y), (x+d*6, y-3), (x+d*6, y+3)]], fill=BLACK)
    draw.text(((lo+hi)*S/2, (y+5)*S), "Idle time", font=italic, fill=BLACK, anchor="mt")


# 顶层两框与下面两组的左右边界一致，不再向右多出一截。
main_title = ink((250, 10, 730, 49))
parameter_title = ink((1070, 10, 1310, 49))
box((14, 3, 1435, 293))
frame((17, 6, 852, 291), title_patch=main_title, fill=True)
frame((864, 6, 1431, 291), title_patch=parameter_title, fill=True)
crop_to((974, 58, 1410, 187), 914, 58)
crop_to((974, 188, 1160, 210), 914, 188)
paste(job_patches[2], 1098, 218)
dot(1198, 218)
dot(1098, 250, 2.4)
paste(s3, 1098, 260, True)
paste(c_labels[2], 1204, 200)
paste(ink((34, 118, 68, 144)), 34, 118)
paste(ink((34, 226, 68, 252)), 34, 226)
for x in [E, D]:
    dashed(x, 58, 145)
axis(80, 835, 145)
axis(80, 835, 253)
for y in [153, 261]:
    paste(time_label, 804, y)

schedule = [(1, 150, 280, 145, 211, 349), (3, 447, 547, 145, E, D),
            (5, 664, 786, 145, 710, 828), (2, 145, 267, 253, 300, 340),
            (4, 267, 412, 253, 365, 445), (6, 412, 559, 253, 490, 535)]
for j, lo, hi, y, e, d in schedule:
    assert abs(job(j, lo, y)-hi) < 1e-8
    window(e, d, 85 if y == 145 else 198, window_labels[j-1])
idle(280, 447, 154)
idle(547, 664, 154)
for lo, hi, color, text in [(267, 300, BLUE, "早到"), (535, 559, RED, "拖期")]:
    line([(lo, 263), (hi, 263)], color, 2)
    for x in [lo, hi]:
        line([(x, 260), (x, 266)], color, 1)
    draw.text(((lo+hi)*S/2, 269*S), text, font=small_song, fill=color, anchor="mt")

# 左侧三段合成一个外框，右侧两段也合成一个外框，内部以横线分区。
for x0, x1 in [(17, 852), (864, 1431)]:
    box((x0-2, 347, x0+8, 1015))
    box((x1-7, 347, x1+4, 1015))
    box((x0-2, 552, x1+4, 569))
    line([(x0+13, 562), (x1-13, 562)], FRAME, .8)
box((15, 806, 856, 823))
line([(30, 815), (839, 815)], FRAME, .8)
box((18, 304, 851, 347))
box((865, 304, 1430, 347))
frame((17, 303, 852, 1013), "完工偏差与成本")
frame((864, 303, 1431, 1013), "窗口外加工量与成本")

# 拖期备选位置跨过 d3，只有加工条的后一半在窗口之外。
box((129, 451, 836, 551))
box((E+1, 450, D-1, 514), BAND)
for x in [E, D]:
    dashed(x, 450, 514)
window(E, D, 479, window_labels[2])
axis(132, 835, 514)
paste(src.crop(bounds((560, 482, 660+1/S, 514+1/S))), 557, 482)
dot(657, 482)
dot(557, 514, 2.4)
paste(c_labels[2], 663, 464)
paste(s3, 557, 524, True)
paste(e3, E, 524, True)
paste(d3, D, 524, True)
box((D, 517, 657, 525), "white", RED, .8)
for x in range(D-8, 657, 5):
    a, b = max(x, D), min(x+8, 657)
    if b > a:
        line([(a, 525-(a-x)), (b, 525-(b-x))], RED, .7)
paste(t3, 636, 529, True)

# 共同定义移到费用函数处；页脚只保留图例和备选执行位置的说明。
phi = ink((250, 637, 815, 666))
definitions = ink((930, 1032, 1279, 1066))
positive = ink((1148, 1032, 1279, 1066))
note = ink((1280, 1032, 1447, 1067))
box((248, 612, 818, 666))
paste(phi, 435, 616, True)
paste(definitions, 435, 644, True)
box((928, 1029, 1447, 1080))
paste(note, 1280, 1037)
work_formulas = [ink(r) for r in [(1018, 641, 1415, 677), (1018, 677, 1415, 713),
                                  (1018, 713, 1415, 739)]]
box((1015, 612, 1418, 741))
box((1037, 744, 1243, 762))
for formula, y in zip(work_formulas, [622, 661, 700]):
    paste(formula, 1148, y, True)
paste(positive, 1148, 733, True)
# 外框变窄后将蓝色平台高度标记移到平台上方，避免左边缘裁字。
box((871, 786, 901, 822))
math_font = ImageFont.truetype("C:/Windows/Fonts/timesi.ttf", 22*S)
sub_font = ImageFont.truetype("C:/Windows/Fonts/timesi.ttf", 14*S)
draw.text((922*S, 777*S), "αp", font=math_font, fill=BLUE, anchor="lt")
advance = draw.textlength("αp", font=math_font)
draw.text((922*S+advance, 791*S), "3", font=sub_font, fill=BLUE, anchor="lt")

# 检查例子、分组边界、窗口和实际加工条栅格宽度。
assert schedule[3][2] < schedule[3][4] and schedule[5][2] > schedule[5][5]
for y in [145, 253]:
    row = sorted((a, b) for _, a, b, yy, _, _ in schedule if yy == y)
    assert all(row[k][1] <= row[k+1][0] for k in range(len(row)-1))
assert schedule[4][2] == schedule[5][1]
assert 557 < D < 657 and D-557 == 657-D == P/2
j3_boxes = [(447, 113, 547, 145), (1098, 218, 1198, 250), (180, 385, 280, 417),
            (557, 482, 657, 514), (990, 393, 1090, 425), (1190, 486, 1290, 518)]
pink = np.asarray(src)[(113+8)*S, (447+4)*S]
pixels = np.asarray(canvas)
widths = []
for x0, y0, x1, y1 in j3_boxes:
    region = pixels[y0*S:(y1+1)*S, x0*S:x1*S+1]
    columns = np.flatnonzero(np.any(np.all(region == pink, axis=2), axis=0))
    widths.append(int(columns[-1]-columns[0]+1))
assert len(set(widths)) == 1
assert np.all(pixels[1030*S:1075*S, 930*S:1278*S] == 255)
for x in [17, 852, 864, 1431]:
    assert tuple(pixels[700*S, x*S]) == FRAME
canvas.save(OUT/"work1-layout-4k.png", dpi=(300, 300))
canvas.resize((1448, 1086), Image.Resampling.LANCZOS).save(OUT/"work1-layout.png")
(OUT/"layout-check.json").write_text(json.dumps({
    "source": str(BASE.relative_to(ROOT)), "export_size": list(canvas.size),
    "left_column": [17, 852], "right_column": [864, 1431], "aligned_top_and_bottom": True,
    "left_outer_group": [17, 303, 852, 1013], "right_outer_group": [864, 303, 1431, 1013],
    "shared_completion_window": [E, D], "partial_late_job": [557, 657],
    "main_schedule": schedule, "early_job": 2, "tardy_job": 6,
    "raster_pink_widths": widths, "definitions_moved_to_functions": True,
    "pass": True}, ensure_ascii=False, indent=2), encoding="utf-8")
print("Layout exported: aligned columns, grouped costs, nonzero main examples, partial tardiness and equal J3 bars PASS.")
