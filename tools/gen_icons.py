# -*- coding: utf-8 -*-
"""生成 launcher 图标 PNG（API < 26 使用），依赖 Pillow。"""
import os
import math
from PIL import Image, ImageDraw

RES = r"D:\My\文档\Workspace\BeijingClock\app\src\main\res"
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

WHITE = (255, 255, 255, 255)
BG_TOP = (21, 92, 160, 255)
BG_BOTTOM = (8, 44, 88, 255)

SS = 4  # 超采样倍数，用于抗锯齿


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def draw_gradient(size):
    grad = Image.new("RGBA", (1, size))
    px = grad.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = (
            round(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t),
            round(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t),
            round(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t),
            255,
        )
    return grad.resize((size, size), Image.NEAREST)


def clock_layer(size, mode):
    """在 SS 倍画布上绘制表盘。

    mode: 'square' 圆角矩形底 + 白色表盘；'round' 正圆底 + 白色表盘；'plain' 纯透明白表盘
    """
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = s / 2.0

    if mode == "square":
        radius = int(s * 0.20)
        bg = draw_gradient(s).convert("RGBA")
        img.paste(bg, (0, 0), rounded_mask(s, radius))
    elif mode == "round":
        bg = draw_gradient(s).convert("RGBA")
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
        img.paste(bg, (0, 0), mask)

    # 表盘直径
    dia = s * (0.60 if mode == "square" else 0.72)
    r = dia / 2.0
    ring_w = dia * 0.075

    # 外圈
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=WHITE, width=max(1, int(round(ring_w))))

    # 12 个刻度
    tick_w = max(1, int(round(dia * 0.055)))
    tick_len = dia * 0.14
    for i in range(12):
        a = math.radians(i * 30.0)
        outer = r - ring_w * 1.35
        inner = outer - tick_len
        sx = cx + math.sin(a) * outer
        sy = cy - math.cos(a) * outer
        ex = cx + math.sin(a) * inner
        ey = cy - math.cos(a) * inner
        d.line([sx, sy, ex, ey], fill=WHITE, width=tick_w, joint="curve")
        d.ellipse([sx - tick_w / 2, sy - tick_w / 2, sx + tick_w / 2, sy + tick_w / 2], fill=WHITE)
        d.ellipse([ex - tick_w / 2, ey - tick_w / 2, ex + tick_w / 2, ey + tick_w / 2], fill=WHITE)

    # 时针（指向 10 点）
    hour_len = r * 0.46
    hour_w = max(1, int(round(dia * 0.085)))
    a = math.radians(((10 + 10 / 60.0) / 12.0) * 360.0)
    hx = cx + math.sin(a) * hour_len
    hy = cy - math.cos(a) * hour_len
    d.line([cx, cy, hx, hy], fill=WHITE, width=hour_w)
    for (px, py) in ((cx, cy), (hx, hy)):
        d.ellipse([px - hour_w / 2, py - hour_w / 2, px + hour_w / 2, py + hour_w / 2], fill=WHITE)

    # 分针（指向 2 点）
    min_len = r * 0.68
    min_w = max(1, int(round(dia * 0.062)))
    a = math.radians((10 / 60.0) * 360.0)
    mx = cx + math.sin(a) * min_len
    my = cy - math.cos(a) * min_len
    d.line([cx, cy, mx, my], fill=WHITE, width=min_w)
    for (px, py) in ((mx, my),):
        d.ellipse([px - min_w / 2, py - min_w / 2, px + min_w / 2, py + min_w / 2], fill=WHITE)

    # 中心点
    dot_r = dia * 0.055
    d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=WHITE)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)

        square = clock_layer(size, "square")
        square.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")

        round_icon = clock_layer(size, "round")
        round_icon.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")

        # 校验
        with Image.open(os.path.join(out_dir, "ic_launcher.png")) as im:
            print(f"{folder}: {im.size[0]}x{im.size[1]} {im.mode} -> ic_launcher.png ok")
        with Image.open(os.path.join(out_dir, "ic_launcher_round.png")) as im:
            print(f"{folder}: {im.size[0]}x{im.size[1]} {im.mode} -> ic_launcher_round.png ok")


if __name__ == "__main__":
    main()
