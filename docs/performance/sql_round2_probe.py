"""Additional actual-DAO distributions; desktop SQLite, not Android or Room timings."""
import json
import sqlite3
import argparse
from sql_probe import database, probe, queries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--schema-version', type=int, default=5)
    args = parser.parse_args()
    sql = queries()
    output = {"scope": f"Desktop SQLite in memory; actual schema {args.schema_version}/DAO SQL; 20 warm runs per query",
              "sqlite": sqlite3.sqlite_version, "samples": []}
    for size in (1000, 10000):
        db = database(size, args.schema_version)
        sample = {"rows": size, "queries": {}}
        sample["queries"]["channelStats_10_buckets"] = probe(db, sql["channelStats"], {"bucketKey": "bucket.0", "excludeId": str(size-1)})
        db.execute("UPDATE observed_event SET bucketKey='one-bucket'")
        db.commit()
        sample["queries"]["channelStats_one_bucket"] = probe(db, sql["channelStats"], {"bucketKey": "one-bucket", "excludeId": str(size-1)})
        sample["queries"]["latencies_all_model"] = probe(db, sql["observeLatencies"], {})
        # Only older 100 rows are model decisions; the other rows are local decisions.
        db.execute("""UPDATE observed_event SET
            decidedBy=CASE WHEN CAST(id AS INTEGER)<100 THEN 'MODEL' ELSE 'OTP_GATE' END,
            latencyMs=CASE WHEN CAST(id AS INTEGER)<100 THEN latencyMs ELSE 0 END,
            uploaded=CASE WHEN CAST(id AS INTEGER)<100 THEN 1 ELSE 0 END,
            action=CASE WHEN CAST(id AS INTEGER)<100 THEN action ELSE 'ALLOW' END""")
        db.commit()
        sample["queries"]["latencies_100_old_model"] = probe(db, sql["observeLatencies"], {})
        sample["queries"]["recent_1KiB"] = probe(db, sql["observeRecent"], {"limit": 300})
        sample["recent_body_bytes_1KiB"] = db.execute("SELECT SUM(LENGTH(body)) FROM (SELECT body FROM observed_event ORDER BY timestampMs DESC LIMIT 300)").fetchone()[0]
        # Keep the rest small; make the latest 300 messages long.
        db.execute("UPDATE observed_event SET body=? WHERE CAST(id AS INTEGER)>=?", ("x" * 16384, size-300))
        db.commit()
        sample["queries"]["recent_16KiB"] = probe(db, sql["observeRecent"], {"limit": 300})
        sample["queries"]["recent_summaries_16KiB"] = probe(db, sql["observeRecentSummaries"], {"limit": 300})
        sample["summary_body_bytes"] = 0
        sample["recent_body_bytes_16KiB"] = db.execute("SELECT SUM(LENGTH(body)) FROM (SELECT body FROM observed_event ORDER BY timestampMs DESC LIMIT 300)").fetchone()[0]
        db.execute("UPDATE observed_event SET decidedBy='OTP_GATE'")
        sample["queries"]["latencies_zero_model"] = probe(db, sql["observeLatencies"], {})
        db.execute("UPDATE observed_event SET decidedBy='MODEL', latencyMs=123 WHERE CAST(id AS INTEGER)>=?", (size-100,))
        sample["queries"]["latencies_100_recent_model"] = probe(db, sql["observeLatencies"], {})
        db.execute("UPDATE observed_event SET decidedBy=CASE WHEN CAST(id AS INTEGER)%2=0 THEN 'MODEL' ELSE 'FALLBACK' END, latencyMs=123")
        sample["queries"]["latencies_dense_mixed"] = probe(db, sql["observeLatencies"], {})
        output["samples"].append(sample)
        db.close()
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
