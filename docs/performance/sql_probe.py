"""Probe current Room SQL with synthetic data; timings are not Android measurements.

Run: python docs/performance/sql_probe.py > docs/performance/sql_probe_after.json
Before: python docs/performance/sql_probe.py --schema-version 3 --revision e55eb15
Uses only the standard library and the repository's actual Room schemas and DAO SQL.
"""
import io
import argparse
import json
from pathlib import Path
import platform
import re
import sqlite3
import statistics
import sys
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "core/data/schemas/me.ethanxu.jevnoisegate.core.data.JevNoiseGateDatabase"
DAO = ROOT / "core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt"


def queries(revision=None):
    pattern = r'@Query\(\s*(?:"""([\s\S]*?)"""|"([^"\n]*)")\s*,?\s*\)[\s\S]*?\bfun\s+(\w+)'
    source = DAO.read_text(encoding="utf-8") if revision is None else subprocess.run(
        ["git", "show", f"{revision}:{DAO.relative_to(ROOT).as_posix()}"], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8", check=True).stdout
    result = {name: (multi or single).strip() for multi, single, name in re.findall(pattern, source)}
    summary = re.search(r'private const val SUMMARY_COLUMNS = ([\s\S]*?)\n\n', source)
    if summary:
        columns = ''.join(re.findall(r'"([^"\n]*)"', summary[1]))
        result = {name: query.replace('$SUMMARY_COLUMNS', columns) for name, query in result.items()}
    return result


def database(size, schema_version):
    db = sqlite3.connect(":memory:")
    schema = SCHEMAS / f"{schema_version}.json"
    for entity in json.loads(schema.read_text(encoding="utf-8"))["database"]["entities"]:
        name = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", name))
    if schema_version >= 5:
        triggers = (ROOT / 'core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/BucketHistory.kt').read_text(encoding='utf-8')
        for trigger in re.findall(r'"""([\s\S]*?)"""', triggers):
            db.execute(trigger)
    db.executemany(
        "INSERT INTO observed_event (id,source,timestampMs,packageName,channelId,body,bucketKey,fingerprint,skeleton,action,decidedBy,latencyMs,uploaded,decidedAtMs) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [(str(i), "SMS" if i % 5 == 0 else "NOTIFICATION", i * 1000,
          f"app.{i % 20}", f"ch.{i % 4}", "x" * 1024, f"bucket.{i % 10}",
          f"fp.{i % 100}", "x" * 40, "QUARANTINE" if i % 4 == 0 else "ALLOW",
          "MODEL", 100 + i % 1000, 1, i * 1000 + 100) for i in range(size)],
    )
    if schema_version >= 4:
        db.execute("UPDATE observed_event SET capturedAtMs = timestampMs")
    db.commit()
    return db


def probe(db, sql, parameters):
    plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, parameters)]
    ticks = [0]

    def progress():
        ticks[0] += 1
        return 0

    db.execute("SAVEPOINT probe")
    db.set_progress_handler(progress, 1)
    rows = db.execute(sql, parameters).fetchall()
    db.set_progress_handler(None, 0)
    db.execute("ROLLBACK TO probe")
    db.execute("RELEASE probe")
    times = []
    for _ in range(20):
        db.execute("SAVEPOINT probe")
        start = time.perf_counter_ns()
        db.execute(sql, parameters).fetchall()
        times.append((time.perf_counter_ns() - start) / 1e6)
        db.execute("ROLLBACK TO probe")
        db.execute("RELEASE probe")
    return {"plan": plan, "vm_steps": ticks[0],
            "result_rows": len(rows), "median_ms_20_runs": round(statistics.median(times), 4)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema-version", type=int, default=5)
    parser.add_argument("--revision")
    args = parser.parse_args()
    sql = queries(args.revision)
    output = {"python": platform.python_version(), "sqlite": sqlite3.sqlite_version,
              "scope": "Desktop SQLite in memory, warm synthetic 1 KiB bodies; no Room, Binder, disk, network or device measurements.",
              "schema_version": args.schema_version, "revision": args.revision or "working-tree", "samples": []}
    for size in (1000, 10000):
        db = database(size, args.schema_version)
        parameters = {
            "prune": {"cutoffMs": 0},
            "recentSuppressedSms": {"sinceMs": size * 1000 - 60000},
            "observeQuarantined": {"limit": 100},
            "observeQuarantineCount": {},
            "observeTopChannels": {"limit": 10},
            "observeActionCounts": {},
            "observeBackendAttemptCount": {},
            "channelStats": {"bucketKey": "bucket.0", "excludeId": str(size - 1)},
            "eventCount": {},
            "trimOldest": {"excess": 1},
            "pruneExpired": {"cutoffMs": 0},
            "observeOverview": {},
            "observeQuarantineBefore": {"beforeTimestamp": size * 1000 // 2, "beforeId": str(size // 2), "limit": 101},
            "observeSmsOwners": {"body": "x" * 1024, "sinceMs": size * 1000 - 60000},
        }
        ingestion_times = []
        for _ in range(20):
            db.execute("SAVEPOINT ingestion")
            start = time.perf_counter_ns()
            db.execute("INSERT OR IGNORE INTO observed_event (id,source,timestampMs,packageName,body,bucketKey,fingerprint,skeleton) VALUES ('new','NOTIFICATION',?,'app','body','bucket','fp','body')", (size * 1000 + 1000,))
            if "eventCount" in sql:
                excess = db.execute(sql["eventCount"]).fetchone()[0] - 10000
                if excess > 0: db.execute(sql["trimOldest"], {"excess": excess})
            else:
                db.execute(sql["prune"], {"cutoffMs": 0})
            ingestion_times.append((time.perf_counter_ns() - start) / 1e6)
            db.execute("ROLLBACK TO ingestion")
            db.execute("RELEASE ingestion")
        output["samples"].append({"rows": size,
            "allocated_bytes": db.execute("PRAGMA page_count").fetchone()[0] * db.execute("PRAGMA page_size").fetchone()[0],
            "ingestion_median_ms_20_runs": round(statistics.median(ingestion_times), 4),
            "queries": {name: probe(db, sql[name], params) for name, params in parameters.items() if name in sql}})
        db.close()
    print(json.dumps(output, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    main()
