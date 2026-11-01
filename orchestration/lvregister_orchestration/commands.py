"""Small commands for the Makefile, run in the dagster-code container:

    python -m lvregister_orchestration.commands capture-now [--date YYYY-MM-DD]
    python -m lvregister_orchestration.commands sync-partitions
    python -m lvregister_orchestration.commands status
"""

import argparse
import datetime as dt
import os
import re

import dagster as dg
from dagster_graphql import DagsterGraphQLClient

from .partitions import capture_date, captures

LOCATION = "lv_register"


def capture_now(date: str) -> None:
    """Adds the capture partition and queues capture_pipeline for it, as the schedule does."""
    dg.DagsterInstance.get().add_dynamic_partitions(captures.name, [date])
    client = DagsterGraphQLClient(os.environ.get("DAGSTER_WEBSERVER_HOST", "dagster-webserver"), port_number=3000)
    run_id = client.submit_job_execution("capture_pipeline", repository_location_name=LOCATION,
                                         tags={"dagster/partition": date})
    print(f"capture {date}: run {run_id} queued, follow it on http://localhost:3000/runs/{run_id}")


def lake_captures() -> list[str]:
    """Every capture ever made: the raw manifests (never pruned), read with DuckDB over S3."""
    import duckdb

    endpoint = os.environ.get("S3_ENDPOINT", "http://lake:8333")
    con = duckdb.connect(config={"extension_directory": "/opt/duckdb/extensions", "autoinstall_known_extensions": False})
    con.execute("LOAD httpfs")
    con.execute("CREATE SECRET (TYPE s3, KEY_ID ?, SECRET ?, REGION ?, ENDPOINT ?, URL_STYLE 'path', USE_SSL ?)",
                [os.environ.get("S3_ACCESS_KEY", "lvdev"), os.environ.get("S3_SECRET_KEY", ""),
                 os.environ.get("S3_REGION", "us-east-1"), re.sub(r"^https?://", "", endpoint),
                 endpoint.startswith("https")])
    bucket = os.environ.get("S3_BUCKET_RAW", "lv-raw")
    files = con.execute(f"SELECT file FROM glob('s3://{bucket}/date=*/manifest.json')").fetchall()
    return sorted({m.group(1) for (f,) in files if (m := re.search(r"date=(\d{4}-\d{2}-\d{2})/", f))})


def sync_partitions() -> None:
    """Adds a partition for every capture already in the lake (made before Dagster, or by make run)."""
    instance = dg.DagsterInstance.get()
    known = set(instance.get_dynamic_partitions(captures.name))
    missing = [d for d in lake_captures() if d not in known]
    instance.add_dynamic_partitions(captures.name, missing)
    print(f"added partitions: {missing or 'none'}; all: {sorted(known | set(missing))}")


def status() -> None:
    instance = dg.DagsterInstance.get()
    for state in instance.all_instigator_state():
        shown = "RUNNING (default in code)" if state.status.value == "DECLARED_IN_CODE" else state.status.value
        print(f"{state.instigator_type.value.lower():8} {state.name:28} {shown}")
    keys = sorted(instance.get_dynamic_partitions(captures.name))
    print(f"captures in Dagster: {len(keys)}, latest {keys[-1] if keys else '-'}")
    for record in instance.get_run_records(limit=5):
        run = record.dagster_run
        print(f"run {run.run_id[:8]} {run.job_name:18} {run.tags.get('dagster/partition', '-'):10} {run.status.value}"
              f" {record.create_timestamp:%Y-%m-%d %H:%M}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="lvregister_orchestration.commands")
    sub = parser.add_subparsers(dest="command", required=True)
    now = sub.add_parser("capture-now", help="queue a capture for today (Riga date) or --date")
    now.add_argument("--date", default=capture_date(dt.datetime.now(dt.UTC)),
                     type=lambda s: dt.date.fromisoformat(s).isoformat())
    sub.add_parser("sync-partitions", help="add partitions for the captures already in the lake")
    sub.add_parser("status", help="schedule and sensor state, captures, last runs")
    args = parser.parse_args()
    if args.command == "capture-now":
        capture_now(args.date)
    elif args.command == "sync-partitions":
        sync_partitions()
    else:
        status()


if __name__ == "__main__":
    main()
