#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 fireworks-tech-graph 生成的语义 SVG 包装成 HyperFrames 合成，
加上「连线按语义顺序逐条描出 → 数据沿箭头持续流动」的动画。

为什么要这么绕：
  fireworks-tech-graph 自带的 animate 只认 12 套写死的编舞拓扑（Style 1 = memory-weave，
  要求恰好 8 条特定语义的边 + 强制方向），本图的 5 步线性管道不在其中。
  所以静态图用它生成，动画自己按同样的视觉语言复现：
  连线 draw-on + persistent data flow。

用法（docs/flow 目录下）：
    python tools/build-from-svg.py

输入：../diagram/jev4bili-flow.svg
输出：./index.html

改了图（重新 render SVG）之后必须重跑本脚本，否则 index.html 里的内联副本会过期。
"""

import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，中文/符号 print 会炸，先强制 UTF-8
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = Path(__file__).resolve().parent.parent
SVG_PATH = ROOT.parent / "diagram" / "jev4bili-flow.svg"
OUT = ROOT / "index.html"

# ── 时间轴（对齐 fireworks 自己的 +2s-settled-flow：1.8s 构建 + 稳定运行 + 5 帧复位）──
DURATION = 5.75          # 总时长（秒）
BUILD_END = 1.80         # 描线构建结束
RESET_START = 5.50       # 开始复位淡出
STAGE_GAP = 0.35         # 每条边描线的错峰间隔
DRAW_DUR = 0.35          # 单条边描线时长
FLOW_TRAVEL = 444.0      # 稳定运行期 dash offset 总位移（≈120 单位/秒）

# 每条边的「流光」配色：底色取原色，流光取同色系浅调 + 更浅的脉冲头
TINTS = {
    "#2563eb": ("#93c5fd", "#dbeafe"),   # 蓝 · ingress
    "#f97316": ("#fdba74", "#ffedd5"),   # 橙 · extract
    "#7c3aed": ("#c4b5fd", "#ede9fe"),   # 紫 · resolve
    "#10b981": ("#6ee7b7", "#d1fae5"),   # 绿 · memory-write
}
DEFAULT_TINT = ("#cbd5e1", "#f1f5f9")

# ── 次级文字色压深 ────────────────────────────────────────────────────────
# fireworks 自带对比度检查的 scope 原文是「default opaque canvas text tokens;
# not a full rendered accessibility audit」——它只查默认 token，不查文字落在
# 彩色节点底上的情形。HyperFrames 按实际渲染像素审计，抓到 gray-500 / slate-500
# 压在三个浅色节点底（#eff6ff / #faf5ff / #fef2f2）上只有 4.42~4.44:1，差 AA 一点。
# 这里把两个次级色压深到安全线以上；在纯白底上反而更清晰，设计意图不变。
CONTRAST_FIX = {
    "#6b7280": "#5f6875",   # node-sub / arrow-label / legend
    "#64748b": "#58636f",   # node-type / section-sub / metric-label / footnote
}

# SVG 里用到的字体族——HyperFrames 要求命名字体必须有 @font-face。
# 这些都是系统自带字体，用 src: local(...) 声明即可满足校验，无需字体文件。
FONT_FAMILIES = [
    "Helvetica Neue", "Helvetica", "Arial", "PingFang SC",
    "Noto Sans CJK SC", "Microsoft YaHei", "Microsoft JhengHei", "SimHei",
]


def parse_svg(src: str):
    """抽出内联内容、边列表、标签列表。"""
    m = re.search(r"<svg\b[^>]*>(.*)</svg>", src, re.S)
    if not m:
        sys.exit("SVG 结构异常：找不到 <svg> 根")
    inner = m.group(1)

    edges = []
    for em in re.finditer(r'<path\b[^>]*data-graph-role="edge"[^>]*>', inner):
        tag = em.group(0)
        eid = re.search(r'id="([^"]+)"', tag)
        d = re.search(r'\sd="([^"]+)"', tag)
        stroke = re.search(r'stroke="([^"]+)"', tag)
        mark = re.search(r'marker-end="([^"]+)"', tag)
        sw = re.search(r'stroke-width="([^"]+)"', tag)
        stage = re.search(r'data-motion-stage="(\d+)"', tag)
        if not (eid and d):
            continue
        edges.append({
            "id": eid.group(1),
            "d": d.group(1),
            "stroke": (stroke.group(1) if stroke else "#64748b").lower(),
            "marker": mark.group(1) if mark else "",
            "sw": float(sw.group(1)) if sw else 2.0,
            "stage": int(stage.group(1)) if stage else len(edges) + 1,
        })

    labels = re.findall(r'<text\b[^>]*class="arrow-label"[^>]*>.*?</text>', inner, re.S)
    return inner, sorted(edges, key=lambda e: e["stage"]), labels


def build_streams(edges) -> str:
    """在 edge 之后、node 之前插入流光图层（painter's order：压在连线之上、节点之下）。"""
    out = ["\n  <!-- ── 流光层：稳定运行期沿箭头流动的数据脉冲 ── -->"]
    for e in edges:
        body, head = TINTS.get(e["stroke"], DEFAULT_TINT)
        bw = round(min(4.0, max(3.0, e["sw"] * 1.60)), 2)
        out.append(
            f'  <path id="flow-body-{e["id"]}" class="hf-flow" d="{e["d"]}" fill="none" '
            f'stroke="{body}" stroke-width="{bw}" stroke-linecap="round" '
            f'stroke-linejoin="round" opacity="0" stroke-dasharray="16 25"/>'
        )
        out.append(
            f'  <path id="flow-head-{e["id"]}" class="hf-flow" d="{e["d"]}" fill="none" '
            f'stroke="{head}" stroke-width="2.2" stroke-linecap="round" '
            f'stroke-linejoin="round" opacity="0" stroke-dasharray="6 35"/>'
        )
    return "\n".join(out)


HTML = """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width={W}, height={H}" />
    <title>JEV4Bili · 它怎么工作</title>
    <script src="assets/vendor/gsap.min.js"></script>
    <style>
      /* 图上用的是系统字体栈；HyperFrames 要求命名字体必须有 @font-face，
         系统自带字体用 src: local(...) 声明即可，不需要字体文件。 */
{faces}
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ width: {W}px; height: {H}px; overflow: hidden; background: #ffffff; }}
      #root {{ width: 100%; height: 100%; }}
      #root > svg {{ display: block; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="{CID}" data-start="0" data-duration="{DUR}"
         data-width="{W}" data-height="{H}">
{SVG}
    </div>

    <script>
      (function () {{
        var tl = gsap.timeline({{ paused: true }});
        var EDGES = [{EDGE_JSON}];

        /* 箭头标签：SVG 里是独立的 text，按文档顺序与边一一对应。
           契约要求标签随各自连线一起出现，不能一开始就全亮着。 */
        var LABELS = gsap.utils.toArray("#root .arrow-label");
        gsap.set(LABELS, {{ opacity: 0 }});

        /* 描线：用实测长度设 dash，配合 marker-end 在画完的瞬间才挂上，
           否则箭头头部会从第一帧就悬在终点。 */
        EDGES.forEach(function (e, i) {{
          var el = document.getElementById(e.id);
          if (!el) return;
          var len = el.getTotalLength();
          el.style.strokeDasharray = len + " " + len;
          el.style.strokeDashoffset = len;
          el.style.markerEnd = "none";

          var at = 0.05 + i * {GAP};
          tl.to(el, {{ strokeDashoffset: 0, duration: {DRAW}, ease: "power2.out" }}, at);
          // 线画到终点，箭头头部才落上
          tl.set(el, {{ attr: {{ "marker-end": e.marker }} }}, at + {DRAW} - 0.02);
          // 标签与连线同时落位
          if (LABELS[i]) tl.to(LABELS[i], {{ opacity: 1, duration: 0.15, ease: "power1.out" }}, at + {DRAW});

          // 流光：描线完成后淡入，沿箭头持续前进
          var body = document.getElementById("flow-body-" + e.id);
          var head = document.getElementById("flow-head-" + e.id);
          if (body) {{
            tl.to(body, {{ opacity: 0.9, duration: 0.15, ease: "power1.out" }}, {BUILD_END});
            tl.fromTo(body,
              {{ strokeDashoffset: 0 }},
              {{ strokeDashoffset: -{TRAVEL}, duration: {HOLD}, ease: "none" }},
              {BUILD_END});
          }}
          if (head) {{
            tl.to(head, {{ opacity: 1, duration: 0.15, ease: "power1.out" }}, {BUILD_END});
            tl.fromTo(head,
              {{ strokeDashoffset: -10 }},
              {{ strokeDashoffset: -{TRAVEL_10}, duration: {HOLD}, ease: "none" }},
              {BUILD_END});
          }}
        }});

        /* 复位：末端把连线与流光一起淡出，让循环回到第 0 帧的「无连线」状态。
           节点、文字、容器全程不动——这是 fireworks 自己的运动契约。 */
        var fadeOut = gsap.utils.toArray("#root .hf-flow");
        EDGES.forEach(function (e) {{
          var el = document.getElementById(e.id);
          if (el) fadeOut.push(el);
        }});
        gsap.utils.toArray("#root .arrow-label").forEach(function (l) {{ fadeOut.push(l); }});
        tl.to(fadeOut, {{ opacity: 0, duration: {RESET}, ease: "none" }}, {RESET_START});

        window.__timelines["{CID}"] = tl;
        tl.seek(0);
      }})();
    </script>
  </body>
</html>
"""


def main() -> int:
    if not SVG_PATH.exists():
        sys.exit(f"找不到 {SVG_PATH}，请先用 fireworks 渲染出 SVG")

    src = SVG_PATH.read_text(encoding="utf-8")
    inner, edges, labels = parse_svg(src)

    # 对比度修正（来由见 CONTRAST_FIX 注释）
    for old, new in CONTRAST_FIX.items():
        inner = inner.replace(old, new).replace(old.upper(), new)

    vm = re.search(r'viewBox="0 0 (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)"', src)
    if not vm:
        sys.exit("SVG 缺少 viewBox")
    W, H = int(float(vm.group(1))), int(float(vm.group(2)))

    if len(edges) != len(labels):
        print(f"  ! 边数({len(edges)}) 与箭头标签数({len(labels)}) 不一致，标签配对可能错位")

    streams = build_streams(edges)
    svg_attrs = re.search(r"<svg\b([^>]*)>", src).group(1)
    svg_block = f"      <svg{svg_attrs}>{inner}{streams}\n      </svg>"

    faces = "\n".join(
        f'      @font-face {{ font-family: "{f}"; src: local("{f}"); }}' for f in FONT_FAMILIES
    )

    # 注意：这里是 .format() 的「参数」，不是模板串，所以用单花括号——
    # 双花括号的转义只对模板串本身生效，用在这里会把 {{ }} 原样写进 JS 里。
    edge_json = ",\n          ".join(
        '{ id: "%s", marker: "%s", stage: %d }' % (e["id"], e["marker"], e["stage"])
        for e in edges
    )

    html = HTML.format(
        W=W, H=H, DUR=DURATION, CID="flow",
        SVG=svg_block, EDGE_JSON=edge_json, GAP=STAGE_GAP, DRAW=DRAW_DUR,
        BUILD_END=BUILD_END, HOLD=round(RESET_START - BUILD_END, 2),
        TRAVEL=FLOW_TRAVEL, TRAVEL_10=FLOW_TRAVEL + 10,
        RESET=round(DURATION - RESET_START, 2), RESET_START=RESET_START,
        faces=faces,
    )
    OUT.write_text(html, encoding="utf-8")

    print(f"  ✓ 生成 {OUT.name}  ({W}×{H}, {DURATION}s)")
    print("    描线顺序: " + " → ".join(f"{e['id']}(stage {e['stage']})" for e in edges))
    print(f"    流光: {len(edges)} 条  构建 0–{BUILD_END}s  运行 {BUILD_END}–{RESET_START}s  复位 {RESET_START}–{DURATION}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
