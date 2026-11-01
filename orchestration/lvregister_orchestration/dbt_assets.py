"""The dbt project (transform/) as assets: models after the quality gate, dbt tests as checks."""

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import dagster as dg
from dagster_dbt import (
    DagsterDbtTranslator,
    DagsterDbtTranslatorSettings,
    DbtCliResource,
    DbtProject,
    dbt_assets,
    get_asset_key_for_model,
)

from .partitions import captures

DBT_PROJECT = DbtProject(project_dir=Path(os.environ.get("LV_DBT_PROJECT_DIR", "/work/transform")))
QUALITY_GATE = dg.AssetKey("quality_report")
LAKE_SNAPSHOT = dg.AssetKey("lake_snapshot")
LAKE_SOURCE = "lake"  # the source name in transform/models/sources.yml


class Translator(DagsterDbtTranslator):
    """A model reading a `lake` source depends on the lake_snapshot asset (the Java lake step
    writes all eleven files). Staging models also wait for the quality report, so a blocking
    quality check stops dbt; stg_events reads the events of company_events, which
    quality_report depends on."""

    def get_asset_spec(self, manifest: Mapping[str, Any], unique_id: str, project: DbtProject | None) -> dg.AssetSpec:
        spec = super().get_asset_spec(manifest, unique_id, project)
        node = manifest.get("nodes", {}).get(unique_id, {})
        if node.get("resource_type") != "model":
            return spec
        deps = {LAKE_SNAPSHOT if d.asset_key.path[0] == LAKE_SOURCE else d.asset_key for d in spec.deps}
        if node.get("name", "").startswith("stg_"):
            deps.add(QUALITY_GATE)
        return spec.replace_attributes(deps=sorted(deps, key=lambda k: k.to_user_string()), group_name="dbt")


TRANSLATOR = Translator(settings=DagsterDbtTranslatorSettings(enable_asset_checks=True))


@dbt_assets(manifest=DBT_PROJECT.manifest_path, project=DBT_PROJECT, partitions_def=captures,
            dagster_dbt_translator=TRANSLATOR)
def dbt_models(context: dg.AssetExecutionContext, dbt: DbtCliResource):
    """`dbt build` over the whole lake: the marts are rebuilt on every run."""
    yield from dbt.cli(["build"], context=context).stream()


# The marts the index step loads (MartReader), plus dim_companies for its count check.
INDEXED_MARTS = tuple(get_asset_key_for_model([dbt_models], m) for m in (
    "agg_companies_by_form_status_region", "agg_events_per_capture", "feat_mass_registration_addresses",
    "dim_companies"))
