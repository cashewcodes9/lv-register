"""The Dagster definitions load, and the asset graph, partitions and checks are as planned."""

import datetime as dt
import re
from pathlib import Path

import dagster as dg
import pytest

from lvregister_orchestration import automation
from lvregister_orchestration.definitions import defs
from lvregister_orchestration.java_assets import STEPS, CliContainer
from lvregister_orchestration.partitions import capture_date, captures

JAVA = ["ur_raw_files", "lake_snapshot", "company_events", "quality_report", "search_index", "retention"]
CHECKS_JAVA = Path("/work/contract/Checks.java")


@pytest.fixture(scope="module")
def graph():
    return defs.resolve_asset_graph()


def key(name: str) -> dg.AssetKey:
    return dg.AssetKey(name)


def test_definitions_load():
    dg.Definitions.validate_loadable(defs)
    for job in ("capture_pipeline", "reprocess_capture", "rebuild_marts", "dagster_housekeeping"):
        assert defs.resolve_job_def(job) is not None


def test_java_steps_form_a_chain_with_dbt_between_quality_and_index(graph):
    parents = {name: {k.to_user_string() for k in graph.get(key(name)).parent_keys} for name in JAVA}
    assert parents["ur_raw_files"] == set()
    assert parents["lake_snapshot"] == {"ur_raw_files"}
    assert parents["company_events"] == {"lake_snapshot"}
    assert parents["quality_report"] == {"company_events"}
    assert parents["search_index"] == {"quality_report", "agg_companies_by_form_status_region",
                                       "agg_events_per_capture", "feat_mass_registration_addresses", "dim_companies"}
    assert parents["retention"] == {"search_index"}


def test_every_staging_model_waits_for_the_quality_gate(graph):
    staging = [k for k in graph.get_all_asset_keys() if k.to_user_string().startswith("stg_")]
    assert len(staging) == 12
    for k in staging:
        assert key("quality_report") in graph.get(k).parent_keys
    register = graph.get(key("stg_register")).parent_keys
    assert register == {key("lake_snapshot"), key("quality_report")}


def test_every_asset_has_one_partition_per_capture_with_identity_mapping(graph):
    for k in graph.get_all_asset_keys():
        node = graph.get(k)
        if not node.is_materializable:
            continue
        assert node.partitions_def == captures, k
        for parent in node.parent_keys:
            mapping = graph.get_partition_mapping(k, parent)
            assert isinstance(mapping, dg.IdentityPartitionMapping), (k, parent, mapping)


def test_blocking_checks_are_declared_on_the_java_assets(graph):
    blocking = {(spec.asset_key.to_user_string(), spec.name)
                for k in map(key, JAVA) for spec in graph.assets_def_for_key(k).check_specs
                if spec.blocking}
    assert blocking == {("ur_raw_files", "files_complete"), ("lake_snapshot", "schema_unchanged"),
                        ("company_events", "event_spike"), ("quality_report", "drop_share"),
                        ("quality_report", "register_not_empty")}


def test_dbt_tests_are_asset_checks(graph):
    checks = {c.name for c in graph.asset_check_keys if c.asset_key.to_user_string().startswith(("stg_", "dim_", "fct_"))}
    assert any(name.startswith("not_null") for name in checks)


@pytest.mark.skipif(not CHECKS_JAVA.exists(), reason="Checks.java is mounted by make dagster-test")
def test_check_names_match_the_java_side():
    """The CLI reports the checks named in lvregister.quality.Checks, grouped by asset comments."""
    java: dict[str, set[str]] = {}
    asset = None
    for line in CHECKS_JAVA.read_text().splitlines():
        if m := re.match(r"\s*// ([a-z_]+)$", line):
            asset = m.group(1)
        elif (m := re.search(r'public static final String [A-Z_]+ = "([a-z_]+)";', line)) and asset:
            java.setdefault(asset, set()).add(m.group(1))
    python = {s.asset: {c.name for c in s.checks} for s in STEPS}
    assert java == python


def test_weekly_schedule_adds_the_riga_date_as_partition():
    assert automation.weekly_capture.cron_schedule == "0 6 * * 1"
    assert automation.weekly_capture.execution_timezone == "Europe/Riga"
    with dg.instance_for_test() as instance:
        context = dg.build_schedule_context(instance=instance, repository_def=defs.get_repository_def(),
                                            scheduled_execution_time=dt.datetime(
            2026, 11, 9, 6, 0, tzinfo=dt.timezone(dt.timedelta(hours=2))))
        request = automation.weekly_capture.evaluate_tick(context).run_requests[0]
        assert request.partition_key == "2026-11-09"
        assert instance.get_dynamic_partitions("captures") == ["2026-11-09"]


def test_capture_date_is_the_riga_date():
    assert capture_date(dt.datetime(2026, 11, 8, 21, 59, tzinfo=dt.UTC)) == "2026-11-08"
    assert capture_date(dt.datetime(2026, 11, 8, 22, 0, tzinfo=dt.UTC)) == "2026-11-09"


def test_reprocessing_reads_the_lake_and_never_ur(graph):
    selected = automation.reprocess_capture.selection.resolve(graph)
    assert key("ur_raw_files") not in selected
    assert key("lake_snapshot") not in selected
    assert {key("company_events"), key("search_index"), key("retention"), key("dim_companies")} <= selected


def test_cli_container_gets_only_the_forwarded_environment(monkeypatch):
    monkeypatch.setenv("S3_ENDPOINT", "http://lake:8333")
    monkeypatch.setenv("DAGSTER_DB_PASSWORD", "not-for-the-cli")
    env = CliContainer().environment("run-1")
    assert env["S3_ENDPOINT"] == "http://lake:8333"
    assert env["DAGSTER_RUN_ID"] == env["LV_RUN_ID"] == "run-1"
    assert "DAGSTER_DB_PASSWORD" not in env
    assert env["JAVA_OPTS"] == "-Xmx2g -XX:+UseSerialGC"


def test_download_retries_with_backoff(graph):
    policy = graph.assets_def_for_key(key("ur_raw_files")).op.retry_policy
    assert policy.max_retries == 3
    assert policy.backoff == dg.Backoff.EXPONENTIAL


def test_raw_files_turn_stale_after_eight_days(graph):
    policy = graph.get(key("ur_raw_files")).freshness_policy
    assert policy.fail_window.to_timedelta() == dt.timedelta(days=8)
