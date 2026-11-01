"""The Java steps as assets. Each runs `lv-register <step> --date <partition>` in the cli image
through Dagster Pipes; the CLI reports the materialization metadata and the check results."""

import datetime as dt
import os
from collections.abc import Sequence
from dataclasses import dataclass

import dagster as dg
import docker
from dagster_docker import PipesDockerClient

from .partitions import captures

# Environment the cli container gets from the code server (the same names as .env.example).
FORWARDED_ENV = (
    "S3_ENDPOINT", "S3_REGION", "S3_ACCESS_KEY", "S3_SECRET_KEY",
    "S3_BUCKET_RAW", "S3_BUCKET_LAKE", "S3_BUCKET_MARTS",
    "SERVING_BACKEND", "SERVING_JDBC_URL", "SERVING_DB_USER", "SERVING_DB_PASSWORD",
    "QUALITY_DROP_SHARE", "QUALITY_ROW_CHANGE_SHARE", "QUALITY_EVENT_SPIKE_SHARE",
    "QUALITY_EVENT_SPIKE_MIN", "QUALITY_EVENT_HARD_SHARE",
    "RAW_KEEP", "LAKE_KEEP", "STORAGE_WARN_GB",
    "DUCKDB_MEMORY_LIMIT", "DUCKDB_THREADS", "LOG_LEVEL", "UR_CKAN_URL",
)
CONTAINER_LABEL = "lv-register.pipes"


@dataclass(frozen=True)
class Check:
    name: str
    blocking: bool
    description: str


@dataclass(frozen=True)
class JavaStep:
    asset: str
    step: str
    deps: Sequence[str]
    checks: Sequence[Check]
    description: str


# The check names match lvregister.quality.Checks; the CLI must report every one of them.
STEPS = (
    JavaStep("ur_raw_files", "download", (), (
        Check("files_complete", True, "Every MVP file is in the manifest and not empty."),
        Check("size_change", False, "File sizes within QUALITY_ROW_CHANGE_SHARE of the previous capture."),
    ), "The UR CSV files of the capture in s3://lv-raw, with their sha256 manifest."),
    JavaStep("lake_snapshot", "lake", ("ur_raw_files",), (
        Check("schema_unchanged", True, "No column of the previous capture is missing."),
    ), "Every row of every file as Parquet in s3://lv-lake (all columns text)."),
    JavaStep("company_events", "diff", ("lake_snapshot",), (
        Check("event_spike", True, "No event type above QUALITY_EVENT_HARD_SHARE of the companies."),
    ), "Change events against the previous capture in the lake, whatever its date."),
    JavaStep("quality_report", "quality", ("company_events",), (
        Check("drop_share", True, "At most QUALITY_DROP_SHARE of the rows of a file dropped."),
        Check("register_not_empty", True, "The register file has rows."),
        Check("orphan_roles", False, "No role points to an unknown company."),
        Check("unmapped_values", False, "Every legal form, closed flag and proceeding form is mapped."),
        Check("row_change", False, "Rows per file within QUALITY_ROW_CHANGE_SHARE of the previous capture."),
        Check("duplicate_regcodes", False, "No duplicate registration number in the register."),
        Check("event_volume", False, "No event type above the warning level; no company vanished."),
    ), "Run record and data quality report (reports/date=D/report.json)."),
    JavaStep("search_index", "index", ("quality_report",), (
        Check("counts_match_marts", False, "Companies in the serving store match dim_companies (latest capture)."),
    ), "Companies, events, runs, stats and features in the serving store (PostgreSQL)."),
    JavaStep("retention", "prune", ("search_index",), (
        Check("storage_below_warn", False, "Total bucket size at most STORAGE_WARN_GB."),
    ), "Bounded retention of raw CSVs and lake snapshots (RAW_KEEP, LAKE_KEEP)."),
)

DOWNLOAD_RETRY = dg.RetryPolicy(max_retries=3, delay=300, backoff=dg.Backoff.EXPONENTIAL)

# Turns ur_raw_files red when no capture succeeded for 8 days (warns after 7 days 6 hours, when
# the weekly capture is late). Evaluated by the freshness daemon (dagster.yaml).
RAW_FILES_FRESHNESS = dg.FreshnessPolicy.time_window(fail_window=dt.timedelta(days=8),
                                                     warn_window=dt.timedelta(days=7, hours=6))


class CliContainer(dg.ConfigurableResource):
    """Where and how the cli containers run."""

    image: str = "lv-register/cli:dev"
    network: str = "lv-register_default"
    java_opts: str = "-Xmx2g -XX:+UseSerialGC"

    def environment(self, run_id: str) -> dict[str, str]:
        env = {name: os.environ[name] for name in FORWARDED_ENV if os.environ.get(name)}
        env.update({"JAVA_OPTS": self.java_opts, "DAGSTER_RUN_ID": run_id, "LV_RUN_ID": run_id})
        return env


def remove_finished_containers() -> None:
    """PipesDockerClient stops its containers but does not remove them."""
    docker.from_env().containers.prune(filters={"label": CONTAINER_LABEL})


def java_asset(step: JavaStep, extra_deps: Sequence[dg.AssetKey] = ()) -> dg.AssetsDefinition:
    @dg.asset(
        name=step.asset,
        partitions_def=captures,
        deps=[*step.deps, *extra_deps],
        check_specs=[dg.AssetCheckSpec(c.name, asset=step.asset, blocking=c.blocking, description=c.description)
                     for c in step.checks],
        retry_policy=DOWNLOAD_RETRY if step.step == "download" else None,
        freshness_policy=RAW_FILES_FRESHNESS if step.step == "download" else None,
        group_name="pipeline",
        kinds={"java", "docker"},
        description=f"`lv-register {step.step}`. {step.description}",
    )
    def _asset(context: dg.AssetExecutionContext, pipes_docker: PipesDockerClient, cli: CliContainer):
        try:
            return pipes_docker.run(
                context=context,
                image=cli.image,
                command=[step.step, "--date", context.partition_key],
                env=cli.environment(context.run_id),
                container_kwargs={"network": cli.network, "labels": {CONTAINER_LABEL: step.step}},
            ).get_results()
        finally:
            remove_finished_containers()

    return _asset
