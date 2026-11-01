"""The code location `lv_register` (docker/dagster/workspace.yaml)."""

import dagster as dg
from dagster_dbt import DbtCliResource
from dagster_docker import PipesDockerClient

from . import automation
from .dbt_assets import DBT_PROJECT, INDEXED_MARTS, dbt_models
from .java_assets import STEPS, CliContainer, java_asset

java_assets = [java_asset(step, INDEXED_MARTS if step.asset == "search_index" else ()) for step in STEPS]

defs = dg.Definitions(
    assets=[*java_assets, dbt_models],
    jobs=[automation.capture_pipeline, automation.reprocess_capture, automation.rebuild_marts,
          automation.dagster_housekeeping],
    schedules=[automation.weekly_capture, automation.housekeeping_schedule],
    sensors=[automation.run_failure_alert],
    resources={
        "pipes_docker": PipesDockerClient(),
        "cli": CliContainer(
            image=dg.EnvVar("LV_CLI_IMAGE").get_value("lv-register/cli:dev"),
            network=dg.EnvVar("LV_DOCKER_NETWORK").get_value("lv-register_default"),
            java_opts=dg.EnvVar("CLI_JAVA_OPTS").get_value("-Xmx2g -XX:+UseSerialGC"),
        ),
        "dbt": DbtCliResource(project_dir=DBT_PROJECT),
    },
    # One step after the other in one process: the VM has 1 CPU and 3.8 GB (EXECUTION.md).
    executor=dg.in_process_executor,
)
