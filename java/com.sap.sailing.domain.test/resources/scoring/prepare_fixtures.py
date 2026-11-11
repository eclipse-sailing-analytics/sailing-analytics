#!/usr/bin/env python3
"""Extract offline scoring fixtures from the retained #6218 audit (no network access).

Run with the path to 6218-production-audit. Original captures are never modified.
Fields called expected* are assertions only; the Java loader never imports them.
"""
import hashlib
import json
import sys
from pathlib import Path


SELECTION = {
    "kw2014-49er": "KW 2014 Olympic (49ER)",
    "scl2021-porto-cervo": "SAILING Champions League 2021 - Final",
    "scl2022-vilamoura": "Sailing Champions League 2022",
    "scl2022-travemuende": "Sailing Champions League 2022 - Final",
    "team-racing-2026-final": "German Open Team Race & European Team Racing Qualifier 2026 des PYC (Finale)",
    "munich-2k": "2K Munich Team Race",
    "kite-2024-qn-women": "SOF2024 - QN - Formula Kite Women",
    "windsurf-2024-qn-men": "SOF2024 - QN - iQFOil Men",
    "polish-2025-49erfx": "Open Polish Championships 2025 - 49erFX",
    "49erfx-2017": "49erFX European Championships 2017",
    "automatic-rdg": "Sailing Champions League Qualifier 1 - Vilamoura 2023",
    "kite-2023-a82": "Allianz Regatta 2023 Foiling Kite Women",
}


def main():
    audit = Path(sys.argv[1])
    destination = Path(__file__).resolve().parent
    snapshots = json.loads((audit / "snapshots.json").read_text())
    configurations = json.loads((audit / "configurations.json").read_text())
    groups = json.loads((audit / "all-gwt-metadata.json").read_text())
    for group in groups:
        if group.get("source") == "central-metadata.json":
            group["sha256"] = hashlib.sha256((audit / group["source"]).read_bytes()).hexdigest()
    for identifier, name in SELECTION.items():
        snapshot = next(s for s in snapshots if s["name"] == name and s.get("snapshot"))
        config = next(c for c in configurations if c["name"] == name and c["base"] == snapshot["base"])
        group = next(g for g in groups if g["base"] == snapshot["base"] and any(m["name"] == name for m in g["metadata"]))
        metadata = next(m for m in group["metadata"] if m["name"] == name)
        fixture = extract(snapshot, config, metadata, group)
        if identifier in ("scl2021-porto-cervo", "scl2022-vilamoura"):
            fixture["scheme"] = "LOW_POINT_FIRST_TO_WIN_TWO_RACES"
            fixture["configurationNotes"] = "Scheme corrected per NoR 11.4-11.5 and user-confirmed MongoDB update; captured scheme retained in provenance. Q flag unchanged."
        if identifier == "49erfx-2017":
            fixture["configurationNotes"] = "Per-series discards supplied from SI 20.1.3/20.2.3; REST omits these thresholds. SI 20.4.1 specifies Fleet-Racing-only A8; generic Java behavior is characterized separately."
            for series in fixture["series"]:
                series["discardThresholds"] = [] if series["medal"] else [4]
        if identifier == "kw2014-49er":
            official = json.loads((audit / "kw2014-official-tie-20261111/results.json").read_text())
            tied_ids = {r["Id"] for r in official["EntryResults"] if r.get("Rank") == 66}
            fixture["officialTieSource"] = json.loads((audit / "kw2014-official-tie-20261111/results.meta.json").read_text())
            for row in fixture["competitors"]:
                if row["id"] in tied_ids:
                    row["sportingRank"] = 66
        write(destination, identifier, fixture)
    folder = audit / "match-racing-recheck-20261111"
    snapshot = {"snapshot": json.loads((folder / "results.json").read_text()), "request": json.loads((folder / "results.meta.json").read_text())}
    config = {"configuration": json.loads((folder / "configuration.json").read_text()), "request": json.loads((folder / "configuration.meta.json").read_text())}
    name = snapshot["snapshot"]["name"]
    group = next(g for g in groups if g["base"] == "https://sapsailing.com" and any(m["name"] == name for m in g["metadata"]))
    metadata = next(m for m in group["metadata"] if m["name"] == name)
    write(destination, "marstrand-2016-finals", extract(snapshot, config, metadata, group))
    supplement = json.loads((audit / "fixture-supplement-20261111/captures.json").read_text())
    for row in supplement:
        group = next(g for g in groups if g["base"] == row["base"] and any(m["name"] == row["name"] for m in g["metadata"]))
        metadata = next(m for m in group["metadata"] if m["name"] == row["name"])
        fixture = extract({"snapshot": row["snapshot"], "request": row["snapshotRequest"]},
                          {"configuration": row["configuration"], "request": row["configurationRequest"]}, metadata, group)
        write(destination, metadata["scoringScheme"].lower(), fixture)
    for row in json.loads((audit / "meta-fixture-captures-20261111.json").read_text()):
        if row["group"]["name"] == "Österreichische Segelbundesliga 2022":
            # All overall cells were manually replaced; retain capture, use Danish rank-conversion example instead.
            continue
        group = next(g for g in groups if g["base"] == "https://sapsailing.com" and any(m["name"] == row["parent"]["name"] for m in g["metadata"]))
        metadata = next(m for m in group["metadata"] if m["name"] == row["parent"]["name"])
        identifier = metadata["scoringScheme"].lower()
        fixture = extract({"snapshot": row["parent"], "request": row["parentRequest"]},
                          {"configuration": {"series": []}, "request": row["groupRequest"]}, metadata, group)
        fixture["groupName"] = row["group"]["name"]
        fixture["children"] = []
        for index, child in enumerate(row["children"]):
            child_id = identifier + "-act-" + str(index + 1)
            child_metadata = next(m for m in group["metadata"] if m["name"] == child["name"])
            child_fixture = extract(child, {"configuration": child["configuration"], "request": child["configurationRequest"]}, child_metadata, group)
            if child["name"] == "ESS 2015 Singapore":
                supplement = json.loads((audit / "singapore-series-gwt-20261111.json").read_text())
                assert hashlib.sha256(supplement["raw"].encode()).hexdigest() == supplement["sha256"]
                child_fixture["provenance"]["seriesConfiguration"] = {k: v for k, v in supplement.items() if k != "raw"}
                child_fixture["configurationNotes"] = "Series discard thresholds independently captured via SailingService.getRegattaByName; REST omits them. Aberdeen Qualification discards Q1 at threshold 1."
                for series in child_fixture["series"]:
                    captured = next(s for s in supplement["series"] if s["name"] == series["name"])
                    if captured["discardThresholds"] is not None:
                        series["discardThresholds"] = captured["discardThresholds"]
            write(destination, child_id, child_fixture)
            column = child["snapshot"]["displayName"] or child["name"]
            assert column in row["parent"]["columnNames"], column
            fixture["children"].append({"resource": child_id, "column": column})
        parent_ids = {c["id"] for c in row["parent"]["competitors"]}
        fixture["suppressedIds"] = sorted({c["id"] for child in row["children"] for c in child["snapshot"]["competitors"]} - parent_ids)
        fixture["configurationNotes"] = "Child links/order from group REST response. Overall suppression inferred from child IDs absent from captured overall field. Only explicitly corrected overall cells are imported."
        write(destination, identifier, fixture)


def extract(snapshot, config, metadata, group):
    data = snapshot["snapshot"]
    configuration = config["configuration"]
    columns = metadata["columns"]
    fixture = {
        "name": data["name"], "scheme": metadata["scoringScheme"], "type": data["type"],
        "displayName": data["displayName"],
        "provenance": {"results": snapshot["request"], "configuration": config["request"],
                       "metadata": {k: group[k] for k in ("base", "source", "fetched_at", "sha256") if k in group},
                       "capturedScheme": metadata["scoringScheme"]},
        "timePoint": data["resultTimepoint"],
        "canBoatsOfCompetitorsChangePerRace": data["canBoatsOfCompetitorsChangePerRace"],
        "discardThresholds": metadata["discardThresholds"],
        "series": [], "columns": [], "competitors": [],
    }
    for series in configuration["series"]:
        fixture["series"].append({
            "name": series["name"], "medal": series["isMedalSeries"],
            "parallel": series["fleetsCanRunInParallel"], "reset": series["startsWithZeroScore"],
            "carry": series["firstColumnIsNonDiscardableCarryForward"],
            "contiguous": series["hasSplitFleetContiguousScoring"],
            "maximumDiscards": series["maximumNumberOfDiscards"],
            "fleets": [{k: f[k] for k in ("name", "ordering")} for f in series["fleets"]],
            "columns": series["races"],
        })
    for column in columns:
        fixture["columns"].append({k: column[k] for k in ("name", "factor", "explicitFactor", "oneAlwaysStaysOne")})
    for competitor in data["competitors"]:
        row = {k: competitor[k] for k in ("id", "name", "displayName", "sailID")}
        row.update(expectedRank=competitor["rank"], expectedTotal=competitor["netPoints"], cells=[])
        for column in columns:
            cell = competitor["raceScores"].get(column["name"])
            if cell is not None:
                row["cells"].append({"column": column["name"], "fleet": cell["fleet"],
                                     "points": cell["totalPoints"], "reason": cell["maxPointsReason"],
                                     "isCorrected": cell["isCorrected"],
                                     "expectedNet": cell["netPoints"], "expectedDiscard": cell["isDiscarded"]})
        fixture["competitors"].append(row)
    return fixture


def write(destination, identifier, fixture):
    path = destination / (identifier + ".json")
    text = json.dumps(fixture, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")
    print(identifier, len(fixture["competitors"]), hashlib.sha256(text.encode()).hexdigest())


if __name__ == "__main__":
    main()
