#!/usr/bin/env python3
"""Turns one run's raw files into a readable table:  load-test/summarize.py <result-dir> <redirect|create>"""
import json, re, sys
from pathlib import Path

out, scenario = Path(sys.argv[1]), sys.argv[2]
summary = json.loads((out / f"{scenario}-summary.json").read_text())
server = json.loads((out / f"{scenario}-server.json").read_text())
variant = out.name.split("-", 2)[2]

def metrics(name):
    values = {}
    path = out / f"{scenario}-metrics-{name}.txt"
    if path.exists():
        for line in path.read_text().splitlines():
            m = re.match(r'^([a-z_]+(?:\{[^}]*\})?) ([0-9.eE+-]+)$', line)
            if m:
                values[m.group(1)] = float(m.group(2))
    return values

before, after = metrics("before"), metrics("after")
delta = lambda key: after.get(key, 0) - before.get(key, 0)
ms = lambda x: "-" if x is None else (f"{x:.1f}" if x < 100 else f"{x:.0f}")

lines = [f"### {scenario} / {variant}", "", f"{summary['stepSeconds']} s per step, constant arrival rate.", ""]
if scenario == "redirect":
    lines += ["| target req/s | achieved | p50 ms | p95 ms | p99 ms | max ms | failed | dropped by generator |", "|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for s in summary["steps"]:
        l = s["latencyMs"]
        lines.append(f"| {s['targetRps']} | {s['achievedRps']:.0f} | {ms(l['p50'])} | {ms(l['p95'])} | {ms(l['p99'])} | {ms(l['max'])} | {s['failedPct']}% | {s['droppedIterations']:.0f} |")
    answered = summary["totalRedirectsAnswered"]
    stored = summary.get("clicksStoredAfterDrain")
    lines += ["", "**Bookkeeping** (every redirect that was answered should end up as one stored click)", ""]
    if stored is not None:
        lines.append(f"- redirects answered (302): {answered:,.0f}; clicks stored after draining: {stored:,.0f}; "
                     f"difference: {answered - stored:,.0f} ({(answered - stored) / answered * 100 if answered else 0:.3f}%)")
    lines.append(f"- clicks dropped by the app: {delta('clicks_dropped_total'):.0f}; lost: {delta('clicks_lost_total'):.0f}; "
                 f"duplicates recognised: {delta('clicks_duplicates_total'):.0f}")
    hits, misses = delta('cache_link_total{result="hit"}'), delta('cache_link_total{result="miss"}')
    if hits + misses:
        lines.append(f"- redirect cache: {hits:,.0f} hits, {misses:,.0f} misses ({hits / (hits + misses) * 100:.2f}% hit ratio)")
    unavailable = delta('cache_link_total{result="unavailable"}')
    if unavailable:
        lines.append(f"- redirects served without the cache (Redis off or down): {unavailable:,.0f}")
    total = sum(s["requests"] for s in summary["steps"]) or 1
    cpu = server["cpuSeconds"]
    lines += ["", "**CPU used on this machine during the run**", "",
              f"- application: {cpu['app']:.1f} s, Postgres: {cpu['postgres']:.1f} s, Redis: {cpu['redis']:.1f} s "
              f"(wall {server['wallSeconds']} s, {total:,} measured requests)",
              f"- per 1,000 requests: application {cpu['app'] / total * 1000:.2f} s, Postgres {cpu['postgres'] / total * 1000:.2f} s, Redis {cpu['redis'] / total * 1000:.2f} s"]
else:
    lines += ["| target req/s | achieved | created | p50 ms | p95 ms | p99 ms | max ms | failed |", "|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for s in summary["steps"]:
        l = s["latencyMs"]
        lines.append(f"| {s['targetRps']} | {s['achievedRps']:.0f} | {s['created']:.0f} | {ms(l['p50'])} | {ms(l['p95'])} | {ms(l['p99'])} | {ms(l['max'])} | {s['failedPct']}% |")
print("\n".join(lines))
