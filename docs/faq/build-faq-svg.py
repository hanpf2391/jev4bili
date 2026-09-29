#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成「常见问题」插图（SVG）。

为什么手写而不是用 fireworks-tech-graph：
  那个 skill 是**图表**生成器（架构/时序/流程图/思维导图…），没有 FAQ 卡片这类版式。
  所以这里手写 SVG，但**严格套用它 Style 1 Flat Icon 的设计令牌**——同一套色板、
  圆角、描边宽度、图标底纹做法、字体栈——再走同一条 puppeteer 渲染管线出 PNG，
  这样两张图在 README 里是同一个视觉系统。

字体栈和配色都照搬它的令牌，只把次级灰压深一档（#6b7280→#5f6875），
理由同 how-it-works：gray-500 落在浅色底纹上只有 4.4:1，过不了 WCAG AA。

用法（本目录）：
    python build-faq-svg.py            # 生成 faq.svg
出 PNG：用 fireworks skill 的 svg2png.js 指向本目录（puppeteer 路径，中文必须走它）
"""

import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

OUT = Path(__file__).resolve().parent / "faq.svg"

# ── 排版常量 ──────────────────────────────────────────────────────────────
W, H = 1240, 720
M = 40                    # 画布边距
CARD_W, CARD_H = 560, 156
GAP_X, GAP_Y = 40, 24
COLS = [M, M + CARD_W + GAP_X]                                   # 40 / 640
ROWS = [134, 134 + CARD_H + GAP_Y, 134 + 2 * (CARD_H + GAP_Y)]   # 134 / 314 / 494

FONT = ("'Helvetica Neue', Helvetica, Arial, 'PingFang SC', 'Noto Sans CJK SC', "
        "'Microsoft YaHei', 'Microsoft JhengHei', 'SimHei', sans-serif")

INK, MUTED, BORDER = "#111827", "#5f6875", "#d1d5db"
RULE = "#dbe5f1"

# ── 六条 FAQ：题干、正文、配色、图标 ──────────────────────────────────────
# 文案与 README「文字版（点开）」逐字一致，改 README 时必须同步改这里。
CARDS = [
    # accent 用 App 品牌蓝 colors.xml/primary=#1B61C9，与 how-it-works 和落地页统一
    dict(q="会被 B 站封号吗？", accent="#1B61C9", tint="#eff6ff", edge="#bfdbfe",
         # 手工断行：自动折行会把「屏幕」拆成两行，读起来糙。
         a=["不会。不调用 B 站任何接口、不修改客户端，也不注入进程，",
            "只是读屏幕上你本来就看得见的那几个字——和你看屏幕没什么区别。"],
         icon=[("path", "M12 3 L20 6 V12 C20 16.5 16.5 19.8 12 21 C7.5 19.8 4 16.5 4 12 V6 Z"),
               ("path", "M8.5 12 L11 14.5 L15.5 10")]),

    dict(q="要 root 吗？要装 Xposed 吗？", accent="#16a34a", tint="#f0fdf4", edge="#bbf7d0",
         a=["都不用。纯无障碍方案，装完开一个无障碍开关就能用——不破解、",
            "不改机、不动系统，也不要任何特殊权限；关掉开关就彻底停下。"],
         icon=[("rect", "5 11 19 20"), ("path", "M8 11 V8 C8 5.8 9.8 4 12 4 C13.6 4 15 4.9 15.7 6.2"),
               ("circle", "12 15.6 1.6")]),

    dict(q="它能看到我的隐私吗？", accent="#7c3aed", tint="#faf5ff", edge="#ede9fe",
         a=["看不到。不截图、不读视频内容，只拿卡片上那几个字；零遥测、",
            "零第三方 SDK，代码全摆在 GitHub 上，随你查。"],
         icon=[("path", "M2 12 C5 7 8.5 5 12 5 C15.5 5 19 7 22 12 C19 17 15.5 19 12 19 "
                        "C8.5 19 5 17 2 12 Z"),
               ("circle", "12 12 3"), ("path", "M4.5 19.5 L19.5 4.5")]),

    dict(q="费电吗？", accent="#f97316", tint="#fff7ed", edge="#fed7aa",
         a=["约 1.8%/时，只在你刷 B 站的时候工作。主页「后台耗电」那一栏就是",
            "它自己报的数；不放心的话，把守护暂停掉就一点都不耗了。"],
         icon=[("rect", "3 7 19 17"), ("rect", "20.4 10.4 2 3.2"),
               ("rectfill", "5.6 9.6 8 4.8")]),

    dict(q="判不准怎么办？", accent="#dc2626", tint="#fef2f2", edge="#fee2e2",
         a=["两个旋钮：「判定灵敏度」滑杆往右更严；单个 UP 主可加",
            "白名单／黑名单。同一 UP 主的判定会累积成本地画像，越用越准。"],
         icon=[("path", "M4 7 H20"), ("path", "M4 12 H20"), ("path", "M4 17 H20"),
               ("circlefill", "9 7 2.4"), ("circlefill", "15.4 12 2.4"), ("circlefill", "7 17 2.4")]),

    dict(q="要花钱吗？", accent="#0d9488", tint="#f0fdfa", edge="#ccfbf1",
         a=["App 本身免费开源（GPL-3.0）。判定用的是你自己的 Jev 密钥，费用",
            "直接付给接口方，作者不经手、也看不到你的内容。"],
         icon=[("circle", "12 12 8.6"),
               ("path", "M9 8.4 L12 12.4 M15 8.4 L12 12.4 M9.4 13 H14.6 M9.4 15.6 H14.6 "
                        "M12 12.4 V17.2")]),
]


def tw(s: str, size: float) -> float:
    """粗略字宽：CJK/全角按 1 em，其余按 0.52 em。用于在 SVG 里预折行
    ——SVG 没有自动折行，必须在生成阶段算好。"""
    w = 0.0
    for ch in s:
        w += size if ord(ch) > 0x2E7F else size * 0.52
    return w


# 中文避头尾：这些字符不能出现在行首（会让标点孤零零吊在下一行开头）
NO_START = "。，、；：？！）］｝】》」』〉·…—％‰°′″／"
# 这些不能出现在行尾
NO_END = "（［｛【《「『〈"


def wrap(s: str, size: float, maxw: float):
    """贪心折行 + 避头尾。ch 不能起行时允许本行轻微溢出（肉眼无感），
    断行前把不能留行尾的字符挤到下一行。"""
    lines, cur = [], ""
    for ch in s:
        if tw(cur + ch, size) <= maxw or ch in NO_START:
            cur += ch
            continue
        carry = ""
        while cur and cur[-1] in NO_END:
            carry = cur[-1] + carry
            cur = cur[:-1]
        lines.append(cur)
        cur = carry + ch
    if cur:
        lines.append(cur)
    return lines


def lines_of(c, maxw):
    """a 可以是手工断好的行列表（首选，断点最自然），也可以是整段字符串
    ——后者走自动折行兜底，改文案时忘了手工断行也不会崩。"""
    a = c["a"]
    return a if isinstance(a, list) else wrap(a, 14, maxw)


def icon_svg(items, cx, cy, accent):
    """把 24×24 坐标系的图标居中放进 44×44 的底纹块里。"""
    ox, oy = cx - 12, cy - 12
    out = [f'<g transform="translate({ox},{oy})" fill="none" stroke="{accent}" '
           f'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">']
    for kind, spec in items:
        p = spec.split()
        if kind == "path":
            out.append(f'<path d="{spec}"/>')
        elif kind == "rect":
            x, y, x2, y2 = (float(v) for v in p)
            out.append(f'<rect x="{x}" y="{y}" width="{x2 - x}" height="{y2 - y}" rx="2" ry="2"/>')
        elif kind == "rectfill":
            x, y, x2, y2 = (float(v) for v in p)
            out.append(f'<rect x="{x}" y="{y}" width="{x2 - x}" height="{y2 - y}" rx="1" '
                       f'fill="{accent}" stroke="none"/>')
        elif kind == "circle":
            cxx, cyy, r = (float(v) for v in p)
            out.append(f'<circle cx="{cxx}" cy="{cyy}" r="{r}"/>')
        elif kind == "circlefill":
            cxx, cyy, r = (float(v) for v in p)
            out.append(f'<circle cx="{cxx}" cy="{cyy}" r="{r}" fill="{accent}" stroke="none"/>')
    out.append("</g>")
    return "".join(out)


def build() -> str:
    L = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">',
        "<defs><style>",
        f"  text {{ font-family: {FONT}; }}",
        f"  .title {{ font-size: 30px; font-weight: 700; fill: {INK}; }}",
        f"  .subtitle {{ font-size: 14px; font-weight: 500; fill: {MUTED}; }}",
        f"  .q {{ font-size: 17px; font-weight: 700; fill: {INK}; }}",
        f"  .a {{ font-size: 14px; font-weight: 500; fill: {MUTED}; }}",
        f"  .num {{ font-size: 11px; font-weight: 800; fill: {MUTED}; letter-spacing: 0.08em; }}",
        "</style></defs>",
        f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
        # 标题区
        f'<text class="title" x="{M}" y="58">常见问题</text>',
        f'<text class="subtitle" x="{M}" y="86">看 README 之前，先看这六条</text>',
        f'<path d="M{M} 112 H{W - M}" stroke="{RULE}" stroke-width="1.4" '
        f'stroke-dasharray="6 5" fill="none"/>',
    ]

    for i, c in enumerate(CARDS):
        cx = COLS[i % 2]
        cy = ROWS[i // 2]

        L.append("<g>")
        L.append(f'  <rect x="{cx}" y="{cy}" width="{CARD_W}" height="{CARD_H}" rx="12" '
                 f'fill="#ffffff" stroke="{BORDER}" stroke-width="1.4"/>')
        # 图标底纹块——与 how-it-works 的节点图标同一做法
        L.append(f'  <rect x="{cx + 26}" y="{cy + 26}" width="44" height="44" rx="11" '
                 f'fill="{c["tint"]}" stroke="{c["edge"]}" stroke-width="1.4"/>')
        L.append("  " + icon_svg(c["icon"], cx + 48, cy + 48, c["accent"]))
        # 序号徽标
        L.append(f'  <text class="num" x="{cx + CARD_W - 26}" y="{cy + 34}" '
                 f'text-anchor="end">{i + 1:02d}</text>')
        # 题干
        L.append(f'  <text class="q" x="{cx + 86}" y="{cy + 48}">{c["q"]}</text>')
        # 正文（预折行）
        tx, maxw = cx + 86, CARD_W - 86 - 30
        for j, line in enumerate(lines_of(c, maxw)):
            L.append(f'  <text class="a" x="{tx}" y="{cy + 86 + j * 25}">{line}</text>')
        L.append("</g>")

    # 脚注——与 how-it-works 的 footnote 同一层级
    L.append(f'<text class="subtitle" x="{M}" y="{H - 26}">'
             f'纯无障碍方案 · 不 root · 不调用 B 站任何接口 · 零遥测 · GPL-3.0</text>')
    L.append("</svg>")
    return "\n".join(L)


def main() -> int:
    OUT.write_text(build(), encoding="utf-8")
    print(f"  ✓ 生成 {OUT.name}  ({W}×{H})  {len(CARDS)} 张卡片")
    for i, c in enumerate(CARDS):
        n = len(lines_of(c, CARD_W - 86 - 30))
        print(f"    {i + 1:02d} {c['q']}  正文 {n} 行")
    return 0


if __name__ == "__main__":
    sys.exit(main())
