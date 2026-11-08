import os
import math
import struct
import zlib

def make_png(size, is_round=False):
    scale = 2
    W = size * scale
    H = size * scale
    pixels = bytearray(size * size * 4)

    def in_round_rect(px, py, rx, ry, rw, rh, rad):
        cx = max(rx + rad, min(px, rx + rw - rad))
        cy = max(ry + rad, min(py, ry + rh - rad))
        d = math.hypot(px - cx, py - cy)
        inside_core = (rx + rad <= px <= rx + rw - rad) or (ry + rad <= py <= ry + rh - rad)
        if rx <= px <= rx + rw and ry <= py <= ry + rh:
            if inside_core:
                return -min(px - rx, rx + rw - px, py - ry, ry + rh - py)
            else:
                return d - rad
        else:
            return d - rad

    def in_polygon(px, py, poly):
        inside = False
        n = len(poly)
        j = n - 1
        for i in range(n):
            xi, yi = poly[i]
            xj, yj = poly[j]
            if ((yi > py) != (yj > py)) and (px < (xj - xi) * (py - yi) / (yj - yi + 1e-9) + xi):
                inside = not inside
            j = i
        return inside

    # Dynamic lightning bolt polygon inside the bubble
    bolt_poly = [
        (0.53, 0.28),
        (0.40, 0.44),
        (0.49, 0.44),
        (0.44, 0.60),
        (0.58, 0.41),
        (0.49, 0.41),
    ]

    # Chat bubble tail polygon
    tail_poly = [
        (0.28, 0.58),
        (0.20, 0.72),
        (0.38, 0.62)
    ]

    for y in range(size):
        for x in range(size):
            r_acc, g_acc, b_acc, a_acc = 0, 0, 0, 0
            for sy in range(scale):
                for sx in range(scale):
                    px = (x * scale + sx + 0.5) / W
                    py = (y * scale + sy + 0.5) / H

                    if is_round:
                        d = math.hypot(px - 0.5, py - 0.5) - 0.47
                    else:
                        d = in_round_rect(px, py, 0.05, 0.05, 0.90, 0.90, 0.22)

                    if d > 0.02:
                        continue
                    base_alpha = max(0.0, min(1.0, -d / 0.02 if d > 0 else 1.0))

                    # Background gradient: Indigo / Deep Violet (#312E81 to #6366F1)
                    t_bg = (px + py) / 2.0
                    bg_r = int(49 + t_bg * (99 - 49))
                    bg_g = int(46 + t_bg * (102 - 46))
                    bg_b = int(129 + t_bg * (241 - 129))

                    # Subtle highlight at top edge
                    if py < 0.2:
                        hl = max(0.0, 1.0 - py / 0.2) * 0.25
                        bg_r = int(bg_r + (255 - bg_r) * hl)
                        bg_g = int(bg_g + (255 - bg_g) * hl)
                        bg_b = int(bg_b + (255 - bg_b) * hl)

                    # Chat Bubble (Rect: 0.20, 0.22 to 0.80, 0.62)
                    d_bub = in_round_rect(px, py, 0.20, 0.22, 0.60, 0.40, 0.12)
                    in_tail = in_polygon(px, py, tail_poly)

                    # Drop shadow under bubble
                    d_sh = in_round_rect(px, py - 0.035, 0.20, 0.22, 0.60, 0.40, 0.12)
                    if (d_sh < 0.04 or in_tail) and (d_bub > 0 and not in_tail):
                        sh_alpha = max(0.0, min(0.35, 0.35 * (1.0 - max(0.0, d_sh) / 0.04)))
                        bg_r = int(bg_r * (1.0 - sh_alpha))
                        bg_g = int(bg_g * (1.0 - sh_alpha))
                        bg_b = int(bg_b * (1.0 - sh_alpha))

                    in_bubble = (d_bub <= 0) or in_tail

                    if in_bubble:
                        if in_polygon(px, py, bolt_poly):
                            # Vibrant electric cyan / teal bolt
                            t_bolt = (py - 0.28) / 0.32
                            cur_r = int(79 + t_bolt * (6 - 79))
                            cur_g = int(70 + t_bolt * (182 - 70))
                            cur_b = int(229 + t_bolt * (212 - 229))
                        else:
                            # Crisp white bubble with subtle shading
                            t_b = (py - 0.22) / 0.40
                            cur_r = int(255 - t_b * 10)
                            cur_g = int(255 - t_b * 8)
                            cur_b = int(255 - t_b * 4)
                    else:
                        cur_r, cur_g, cur_b = bg_r, bg_g, bg_b

                    cur_a = int(base_alpha * 255)
                    r_acc += cur_r * cur_a
                    g_acc += cur_g * cur_a
                    b_acc += cur_b * cur_a
                    a_acc += cur_a

            samples = scale * scale
            idx = (y * size + x) * 4
            a_final = a_acc // samples
            if a_final > 0:
                pixels[idx] = min(255, r_acc // a_acc)
                pixels[idx+1] = min(255, g_acc // a_acc)
                pixels[idx+2] = min(255, b_acc // a_acc)
                pixels[idx+3] = a_final
            else:
                pixels[idx:idx+4] = b'\x00\x00\x00\x00'

    raw = b''
    for row in range(size):
        raw += b'\x00' + pixels[row * size * 4 : (row + 1) * size * 4]

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    ihdr = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')

def main():
    root = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.join(root, "app", "src", "main", "res")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
        "mipmap": 192,
    }

    for folder, size in densities.items():
        dpath = os.path.join(res_dir, folder)
        os.makedirs(dpath, exist_ok=True)
        
        # Standard icon
        sq_png = make_png(size, is_round=False)
        with open(os.path.join(dpath, "ic_launcher.png"), "wb") as f:
            f.write(sq_png)

        # Round icon
        round_png = make_png(size, is_round=True)
        with open(os.path.join(dpath, "ic_launcher_round.png"), "wb") as f:
            f.write(round_png)

        print(f"Generated {folder}: {size}x{size} (square & round)")

if __name__ == "__main__":
    main()
