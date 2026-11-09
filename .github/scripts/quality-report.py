#!/usr/bin/env python3
"""Read-only JaCoCo/Vitest dashboard for GitHub summary and offline HTML."""
from __future__ import annotations

import argparse
import html
import json
import os
import re
from pathlib import Path
from xml.etree import ElementTree as ET

MODULES = ("ai", "audit", "auth", "chat", "common", "identity", "knowledge",
           "model", "organization", "ratelimit", "usage", "user")


def ratio(covered: int, missed: int) -> float | None:
    count = covered + missed
    if covered < 0 or missed < 0:
        raise ValueError("Invalid negative coverage counter")
    return covered / count if count else None


def bar(n: float | None, width: int = 16) -> str:
    if n is None:
        return "нет данных"
    count = min(width, max(0, round(n * width)))
    return "▰" * count + "▱" * (width - count)


def pct(n: float | None) -> str:
    return f"{n:.1%}" if n is not None else "н/д"


def backend_stats(xml_path: Path, baseline_path: Path, reports_dir: Path) -> dict:
    if not xml_path.is_file() or not baseline_path.is_file():
        raise ValueError("JaCoCo XML или baseline не найден. Требуется полный Maven verify")
    root = ET.parse(xml_path).getroot()
    stats: dict[str, dict[str, list[int]]] = {
        name: {k: [0, 0] for k in ("LINE", "BRANCH")} for name in MODULES}
    unknown: set[str] = set()
    for package in root.findall("package"):
        parts = package.attrib.get("name", "").split("/")
        if len(parts) < 4 or parts[:3] != ["ru", "safeai", "gateway"]:
            continue
        module = parts[3]
        if module not in stats:
            unknown.add(module)
            continue
        for counter in package.findall("counter"):
            kind = counter.get("type")
            if kind in ("LINE", "BRANCH"):
                stats[module][kind][0] += int(counter.get("covered", "0"))
                stats[module][kind][1] += int(counter.get("missed", "0"))
    if unknown:
        raise ValueError(f"Unrecognized backend modules: {sorted(unknown)}")
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    if baseline.get("_format") != 1 or not isinstance(baseline.get("modules"), dict):
        raise ValueError("Invalid JaCoCo baseline")
    rows = []
    for name in MODULES:
        b = baseline["modules"].get(name)
        if not isinstance(b, dict):
            raise ValueError(f"Missing baseline for {name}")
        rows.append({"name": name, "line": ratio(*stats[name]["LINE"]),
                     "branch": ratio(*stats[name]["BRANCH"]),
                     "baseline_line": b.get("line"), "baseline_branch": b.get("branch"),
                     "line_count": sum(stats[name]["LINE"]),
                     "branch_count": sum(stats[name]["BRANCH"])})
    if not any(row["line"] is not None for row in rows):
        raise ValueError("JaCoCo did not contain any backend packages")
    total_line = [sum(stats[m]["LINE"][i] for m in MODULES) for i in (0, 1)]
    total_branch = [sum(stats[m]["BRANCH"][i] for m in MODULES) for i in (0, 1)]
    tests = {"total": 0, "failed": 0, "skipped": 0, "integration": 0}
    reports = sorted(reports_dir.glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No Surefire TEST-*.xml reports")
    for report in reports:
        tree = ET.parse(report).getroot()
        for case in tree.iter("testcase"):
            tests["total"] += 1
            if case.find("error") is not None or case.find("failure") is not None:
                tests["failed"] += 1
            if case.find("skipped") is not None:
                tests["skipped"] += 1
            if case.get("classname", "").endswith("IT"):
                tests["integration"] += 1
    if tests["total"] == 0:
        raise ValueError("Empty Surefire results")
    return {"title": "Java / JaCoCo и JUnit", "rows": rows,
            "overall": {"line": ratio(*total_line), "branch": ratio(*total_branch),
                        "baseline_line": baseline["modules"]["_total"].get("line"),
                        "baseline_branch": baseline["modules"]["_total"].get("branch")},
            "tests": tests,
            "footnote": (
                "Покрытие JaCoCo объединяет стандартные и локальные IT; "
                "отдельное покрытие по типу теста не измеряется."
            )}


def frontend_stats(summary_path: Path) -> dict:
    if not summary_path.is_file():
        raise ValueError("Vitest coverage-summary.json отсутствует; npm run test:coverage не выполнен")
    data = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("total"), dict):
        raise ValueError("Неверный формат Vitest coverage-summary.json")

    def metric(obj: dict, key: str) -> tuple[int, int]:
        m = obj.get(key)
        if not isinstance(m, dict):
            raise ValueError(f"Missing Vitest {key}")
        covered, total = int(m["covered"]), int(m["total"])
        if covered < 0 or total < covered:
            raise ValueError(f"Invalid Vitest {key} counters")
        return covered, total

    grouped: dict[str, dict[str, list[int]]] = {}
    for filename, val in data.items():
        if filename == "total":
            continue
        path = filename.replace("\\", "/")
        if "/src/features/" in path:
            name = path.split("/src/features/", 1)[1].split("/", 1)[0]
        elif "/src/" in path:
            name = path.split("/src/", 1)[1].split("/", 1)[0]
        else:
            name = "other"
        bucket = grouped.setdefault(name, {k: [0, 0] for k in ("lines", "branches")})
        for key in ("lines", "branches"):
            covered, total = metric(val, key)
            bucket[key][0] += covered
            bucket[key][1] += total - covered
    if not grouped:
        raise ValueError("Vitest per-file coverage is unavailable")
    rows = [{"name": name, "line": ratio(*c["lines"]),
             "branch": ratio(*c["branches"]),
             "line_count": sum(c["lines"]), "branch_count": sum(c["branches"])}
            for name, c in sorted(grouped.items())]
    total_line = metric(data["total"], "lines")
    total_branch = metric(data["total"], "branches")
    return {
        "title": "React / TypeScript / Vitest",
        "rows": rows,
        "overall": {
            "line": ratio(total_line[0], total_line[1] - total_line[0]),
            "branch": ratio(total_branch[0], total_branch[1] - total_branch[0]),
        },
        "footnote": (
            "Группы построены по путям исходников. "
            "E2E и performance тесты не включены в этот процент."
        ),
    }


def markdown(data: dict) -> str:
    overall = data["overall"]
    lines = [f"## 📊 {data['title']}", "",
             f"**Строки:** {pct(overall['line'])} · **Ветвления:** {pct(overall['branch'])}", ""]
    if "tests" in data:
        t = data["tests"]
        lines.extend([
            f"**JUnit:** {t['total']} кейсов, IT: {t['integration']}, "
            f"ошибок: {t['failed']}, пропущено: {t['skipped']}",
            "",
        ])
    lines += ["| Модуль / группа | Строки | Диаграмма | Ветви | Диаграмма | Baseline (строки) |",
              "|---|---:|---|---:|---|---:|"]
    for r in data["rows"]:
        lines.append(
            f"| `{r['name']}` | {pct(r['line'])} | {bar(r['line'])} | "
            f"{pct(r['branch'])} | {bar(r['branch'])} | "
            f"{pct(r.get('baseline_line'))} |"
        )
    lines.extend([
        "", data["footnote"], "",
        "Подробные отчёты HTML доступны в артефактах этого запуска GitHub Actions.",
    ])
    return "\n".join(lines) + "\n"


def html_page(data: dict) -> str:
    def visual(n: float | None, cls: str) -> str:
        if n is None:
            return '<span class="muted">н/д</span>'
        width = max(0, min(100, n * 100))
        return (
            f'<div class="track"><div class="fill {cls}" '
            f'style="width:{width:.2f}%"></div></div>'
            f'<span>{pct(n)}</span>'
        )
    rows = []
    for r in data["rows"]:
        delta = ""
        if r.get("baseline_line") is not None and r["line"] is not None:
            value = (r["line"] - r["baseline_line"]) * 100
            delta = f'{value:+.1f} п.п.'
        rows.append("<tr><th scope=\"row\">" + html.escape(r["name"]) + "</th><td>" +
                    visual(r["line"], "blue") + "</td><td>" + visual(r["branch"], "purple") +
                    "</td><td>" + html.escape(delta) + "</td></tr>")
    o = data["overall"]
    title = html.escape(data["title"])
    note = html.escape(data["footnote"])
    t = data.get("tests")
    tests = (
        f'<p>JUnit: {t["total"]} тестов, IT: {t["integration"]}, '
        f'ошибки: {t["failed"]}, пропущено: {t["skipped"]}</p>'
        if t else ""
    )
    return f'''<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>{title}</title>
  <style>
    body {{
      font: 15px system-ui, Arial, sans-serif;
      max-width: 1200px;
      margin: 34px auto;
      padding: 0 20px;
      background: #f8fafc;
      color: #162131;
    }}
    h1 {{ font-size: 27px; }}
    .cards {{ display: flex; gap: 20px; flex-wrap: wrap; margin: 24px 0; }}
    .card {{
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 14px;
      padding: 18px 24px;
      min-width: 180px;
    }}
    .big {{ font-size: 29px; font-weight: 700; }}
    table {{ width: 100%; border-collapse: collapse; background: white; }}
    td, th {{ padding: 12px; border-bottom: 1px solid #e2e8f0; text-align: left; }}
    td {{ min-width: 130px; }}
    .track {{
      display: inline-block;
      width: 150px;
      max-width: 65%;
      height: 13px;
      border-radius: 10px;
      background: #e5e7eb;
      margin-right: 12px;
      vertical-align: middle;
      overflow: hidden;
    }}
    .fill {{ height: 100%; border-radius: 10px; }}
    .blue {{ background: #3178c6; }}
    .purple {{ background: #7c3aed; }}
    .muted, small {{ color: #64748b; }}
    .note {{ padding-top: 16px; }}
    @media (max-width: 650px) {{
      .track {{ width: 85px; }}
      td {{ min-width: 0; padding: 8px; }}
    }}
  </style>
</head>
<body>
  <h1>{title}</h1>
  <div class="cards">
    <div class="card">
      <div class="muted">Строки</div>
      <div class="big">{pct(o['line'])}</div>
    </div>
    <div class="card">
      <div class="muted">Ветвления</div>
      <div class="big">{pct(o['branch'])}</div>
    </div>
  </div>
  {tests}
  <table>
    <thead><tr>
      <th>Модуль / группа</th><th>Покрытие строк</th>
      <th>Покрытие ветвей</th><th>Δ к baseline</th>
    </tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
  <p class="note muted">{note}</p>
  <small>
    Автономный отчёт из JaCoCo/Vitest.
    Значения измерены в CI, без внешних CDN и трекеров.
  </small>
</body>
</html>'''


def main() -> int:
    p = argparse.ArgumentParser()
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--backend", action="store_true")
    mode.add_argument("--frontend", action="store_true")
    p.add_argument("--repo", type=Path, default=Path("."))
    ns = p.parse_args()
    root = ns.repo.resolve()
    if ns.backend:
        data = backend_stats(root / "backend/target/site/jacoco/jacoco.xml",
                             root / "backend/coverage-baseline.json",
                             root / "backend/target/surefire-reports")
        out = root / "backend/target/quality-dashboard"
    else:
        data = frontend_stats(root / "frontend/coverage/coverage-summary.json")
        out = root / "frontend/coverage"
    out.mkdir(parents=True, exist_ok=True)
    (out / "dashboard.html").write_text(html_page(data), encoding="utf-8")
    summary = markdown(data)
    (out / "summary.md").write_text(summary, encoding="utf-8")
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as target:
            target.write(summary + "\n")
    print(summary)
    print(f"COVERAGE DASHBOARD OK: {out / 'dashboard.html'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
