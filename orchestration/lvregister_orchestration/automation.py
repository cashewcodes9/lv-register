"""Jobs, the weekly schedule, failure alerts and Dagster's own run retention."""

import datetime as dt
import json
import os
import urllib.request

import dagster as dg

from .partitions import CAPTURE_ZONE, capture_date, captures

capture_pipeline = dg.define_asset_job(
    "capture_pipeline", selection=dg.AssetSelection.all(),
    description="All assets of one capture: download → lake → diff → quality → dbt → index → prune.")

reprocess_capture = dg.define_asset_job(
    "reprocess_capture", selection=dg.AssetSelection.assets("company_events").downstream(),
    description="A past capture again from the lake (not from UR): diff → quality → dbt → index → prune.")

rebuild_marts = dg.define_asset_job(
    "rebuild_marts", selection=dg.AssetSelection.groups("dbt"),
    description="dbt only: rebuild the marts and run the dbt tests.")

WEEKLY_CRON = os.environ.get("LV_CAPTURE_CRON", "0 6 * * 1")


@dg.schedule(job=capture_pipeline, cron_schedule=WEEKLY_CRON, execution_timezone=str(CAPTURE_ZONE),
             default_status=dg.DefaultScheduleStatus.RUNNING,
             description="Weekly capture, Mondays 06:00 Europe/Riga (LV_CAPTURE_CRON). make capture-off / capture-on.")
def weekly_capture(context: dg.ScheduleEvaluationContext):
    date = capture_date(context.scheduled_execution_time)
    # Schedules cannot request dynamic partitions in their result, so the partition is added here.
    context.instance.add_dynamic_partitions(captures.name, [date])
    return dg.RunRequest(run_key=date, partition_key=date)



@dg.run_failure_sensor(default_status=dg.DefaultSensorStatus.RUNNING,
                       description="Logs a failed run and posts it to LV_ALERT_WEBHOOK_URL when set.")
def run_failure_alert(context: dg.RunFailureSensorContext):
    run = context.dagster_run
    text = (f"lv-register: run {run.run_id} of {run.job_name} failed"
            f" (capture {run.tags.get('dagster/partition', '-')}): {context.failure_event.message}")
    context.log.error(text)
    url = os.environ.get("LV_ALERT_WEBHOOK_URL", "")
    if url:
        request = urllib.request.Request(url, data=json.dumps({"text": text}).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=10):
            pass


RUN_RETENTION_DAYS = int(os.environ.get("DAGSTER_RUN_RETENTION_DAYS", "90"))


@dg.op(description="Deletes Dagster runs (and their event logs) older than DAGSTER_RUN_RETENTION_DAYS.")
def delete_old_runs(context: dg.OpExecutionContext) -> int:
    cutoff = dt.datetime.now(dt.UTC) - dt.timedelta(days=RUN_RETENTION_DAYS)
    records = context.instance.get_run_records(filters=dg.RunsFilter(created_before=cutoff))
    for record in records:
        context.instance.delete_run(record.dagster_run.run_id)
    context.log.info(f"deleted {len(records)} runs created before {cutoff:%Y-%m-%d}")
    return len(records)


@dg.job(description="Dagster run retention: OSS Dagster has no setting for it, so a daily job deletes old runs.")
def dagster_housekeeping():
    delete_old_runs()


housekeeping_schedule = dg.ScheduleDefinition(
    name="daily_housekeeping", job=dagster_housekeeping, cron_schedule="30 3 * * *",
    execution_timezone=str(CAPTURE_ZONE), default_status=dg.DefaultScheduleStatus.RUNNING)
