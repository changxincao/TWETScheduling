"""汇总同次join的全OR/全set诊断；分档时间为抽样，不解释为全量占比。"""
import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path


def write_csv(path, rows):
    if not rows:
        return
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def fields(line):
    result = {}
    for key, value in re.findall(r"(\w+)=([^\s]+)", line):
        try:
            result[key] = float(value) if "." in value or "e" in value.lower() else int(value)
        except ValueError:
            result[key] = value
    return result


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("logs", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
rounds, bins, widths, cases = [], [], [], []
for log in sorted(args.logs.glob("bitmap-*.log")):
    rid = log.stem
    current, joint, word_bins = [], defaultdict(lambda: defaultdict(int)), defaultdict(lambda: defaultdict(int))
    with log.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if not line.endswith("\n"):
                continue
            if line.startswith("[NG bitmap compare]"):
                row = fields(line)
                row["case"] = rid
                current.append(row)
                rounds.append(row)
            elif line.startswith("[NG bitmap bin]"):
                row = fields(line)
                target = joint[(row["qBin"], row["kBin"])]
                for name in ("groups", "staticK", "staticW", "merges", "sumK", "sumW", "sampledMerges",
                             "orMs", "setMs", "abSamples", "baSamples"):
                    target[name] += row[name]
            elif line.startswith("[NG bitmap width]"):
                row = fields(line)
                target = word_bins[row["wordPowerBin"]]
                target["groups"] += row["groups"]
                target["merges"] += row["merges"]
    if not current:
        continue
    total = {name: sum(row[name] for row in current) for name in (
        "units", "abUnits", "baUnits", "merges", "sumK", "sumW", "orMs", "setMs", "prepMs", "verifyMs",
        "binDiagnosticMs", "fwGroups", "fwLabels", "bwGroups", "bwLabels")}
    # 这是相同组对工作量的分量比，不是mean(W/k)，也不是完整求解提速。
    total["workWordMemberRatio"] = total["sumW"] / total["sumK"] if total["sumK"] else None
    total["orSetTimeRatio"] = total["orMs"] / total["setMs"] if total["setMs"] else None
    total["meanStaticForwardK"] = total["fwLabels"] / total["fwGroups"] if total["fwGroups"] else None
    total["meanMergedK"] = total["sumK"] / total["merges"] if total["merges"] else None
    total["maxForwardK"] = max(row["fwKMax"] for row in current)
    total["maxIndexPayloadBytes"] = max(row["indexPayloadBytes"] for row in current)
    status = "UNKNOWN"
    status_file = args.logs / (rid + ".status.txt")
    if status_file.exists():
        values = dict(line.split("=", 1) for line in status_file.read_text(encoding="utf-8", errors="replace").splitlines() if "=" in line)
        status = values.get("state", status)
    case = dict(case=rid, state=status, rounds=len(current), **total)
    cases.append(case)
    for (q, k), values in joint.items():
        values["staticGroupShare"] = values["groups"] / total["fwGroups"] if total["fwGroups"] else 0
        values["mergeShare"] = values["merges"] / total["merges"] if total["merges"] else 0
        values["sampleOrSetRatio"] = values["orMs"] / values["setMs"] if values["setMs"] else None
        bins.append(dict(case=rid, qBin=q, kBin=k, **values))
    for word_bin, values in word_bins.items():
        widths.append(dict(case=rid, wordPowerBin=word_bin, **values))
    # 报告必须先校验各档确实覆盖原轮汇总，不能将抽样次数当真实合并次数。
    for name, reference in (("groups", total["fwGroups"]), ("staticK", total["fwLabels"]),
                            ("merges", total["merges"]), ("sumK", total["sumK"]), ("sumW", total["sumW"])):
        assert abs(sum(row[name] for row in joint.values()) - reference) < 0.5, (rid, name)

write_csv(args.output / "cases.csv", cases)
write_csv(args.output / "rounds.csv", rounds)
write_csv(args.output / "joint-bins.csv", bins)
write_csv(args.output / "width-bins.csv", widths)
(args.output / "summary.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
lines = ["# Join位图同状态诊断汇总", "", "OR/set使用相同label、分组和可剪名单；只OR结果进入真实join。以下是位图聚合本身的成对计时，不是完整求解时间，也不是已部署的生产提速。", "",
         "| 算例 | 状态 | 完整诊断轮 | 实际合并次数 | OR ms | set ms | OR/set | 静态平均k | 使用加权平均k |",
         "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
for case in cases:
    ratio = "NA" if case["orSetTimeRatio"] is None else f'{case["orSetTimeRatio"]:.3f}'
    merged = "NA" if case["meanMergedK"] is None else f'{case["meanMergedK"]:.2f}'
    lines.append(f'| {case["case"]} | {case["state"]} | {case["rounds"]} | {case["merges"]} | '
                 f'{case["orMs"]:.3f} | {case["setMs"]:.3f} | {ratio} | {case["meanStaticForwardK"]:.2f} | {merged} |')
lines.extend(["", "OR/set>1表示set在本次聚合计时中更快；小工作量、冷JIT和调度噪声不能作为稳定阈值依据。静态组数是各轮组次，不是跨pricing去重组数。joint-bins.csv给出q×k的静态占比、实际合并占比和解释性抽样时间；抽样不代表全量分档时间占比。workWordMemberRatio是sum(W)/sum(k)，不得叫作平均W/k。", ""])
(args.output / "summary.md").write_text("\n".join(lines), encoding="utf-8")
for case in cases:
    print(json.dumps(case, ensure_ascii=False))
