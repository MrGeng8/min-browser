#!/usr/bin/env python3
"""生成 Min 浏览器图标：暗黑商务风
设计：圆角近黑底(#060606) + 细边框(#3A3A3A) + 银白 M(#E9E9E9) + 暗金细线(#B9975B)
纯手写 PNG 编码，不依赖任何第三方库。
"""
import zlib, struct, math, os

S = 192
buf = bytearray(S * S * 4)          # RGBA，初始全透明


def px(x, y, c):
    if 0 <= x < S and 0 <= y < S:
        i = (y * S + x) * 4
        buf[i], buf[i + 1], buf[i + 2], buf[i + 3] = c[0], c[1], c[2], 255


def rrect(x0, y0, x1, y1, rad, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            dx = dy = 0
            if x < x0 + rad:
                dx = x0 + rad - x
            elif x > x1 - rad:
                dx = x - (x1 - rad)
            if y < y0 + rad:
                dy = y0 + rad - y
            elif y > y1 - rad:
                dy = y - (y1 - rad)
            if dx * dx + dy * dy <= rad * rad:
                px(x, y, c)


def line(x0, y0, x1, y1, w, c):
    n = int(math.hypot(x1 - x0, y1 - y0) * 3) + 1
    r = w / 2.0
    for i in range(n + 1):
        t = i / n
        cx, cy = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        for dy in range(int(-r) - 1, int(r) + 2):
            for dx in range(int(-r) - 1, int(r) + 2):
                if dx * dx + dy * dy <= r * r:
                    px(int(round(cx)) + dx, int(round(cy)) + dy, c)


BG      = (6, 6, 6)
BORDER  = (58, 58, 58)
SILVER  = (233, 233, 233)
GOLD    = (185, 151, 91)

# 外框 + 内底
rrect(8, 8, 183, 183, 44, BORDER)
rrect(11, 11, 180, 180, 41, BG)

# M 字（居中）
MX0, MX1, MY0, MY1, MID = 60, 132, 54, 124, 100
W = 13
line(MX0, MY1, MX0, MY0, W, SILVER)          # 左竖
line(MX1, MY1, MX1, MY0, W, SILVER)          # 右竖
line(MX0, MY0, 96, MID, W, SILVER)           # 左斜
line(MX1, MY0, 96, MID, W, SILVER)           # 右斜

# 暗金细线（商务感点缀）
line(MX0, 140, MX1, 140, 4, GOLD)


def chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))


def write_png(path):
    raw = b''.join(b'\x00' + bytes(buf[y * S * 4:(y + 1) * S * 4]) for y in range(S))
    out = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(out)
    return len(out)


if __name__ == '__main__':
    # 本机 density=640 即 xxxhdpi，192x192 正好一一对应，只出一张最省体积
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'res')
    p = os.path.join(base, 'mipmap-xxxhdpi', 'ic.png')
    n = write_png(p)
    print(f'  mipmap-xxxhdpi/ic.png  {S}x{S}  {n} 字节')
