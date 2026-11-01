# Orchestration image (PLAN.md section 6.1): dbt (Milestone 6) and Dagster (Milestone 7).
# Build context: the repository root, so the dbt project and its manifest are in the image.
FROM python:3.12.16-slim-bookworm

ARG HOST_UID=1000
ARG HOST_GID=1000

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1 \
    DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions \
    DAGSTER_HOME=/opt/dagster/home \
    PYTHONPATH=/work/orchestration \
    DBT_PROFILES_DIR=/work/transform \
    LV_DBT_PROJECT_DIR=/work/transform
COPY orchestration/requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt \
 && mkdir -p /opt/duckdb/extensions \
 && python -c "import duckdb; c = duckdb.connect(config={'extension_directory': '/opt/duckdb/extensions'}); c.sql('INSTALL httpfs'); c.sql('INSTALL json')" \
 && (getent group "${HOST_GID}" || groupadd --gid "${HOST_GID}" lvregister) \
 && useradd --create-home --uid "${HOST_UID}" --gid "${HOST_GID}" lvregister \
 && mkdir -p /opt/dagster/home /opt/dagster/storage /work \
 && chown -R "${HOST_UID}:${HOST_GID}" /opt/dagster /work

USER lvregister
COPY --chown=${HOST_UID}:${HOST_GID} transform /work/transform
COPY --chown=${HOST_UID}:${HOST_GID} orchestration /work/orchestration
# The dbt manifest that dagster-dbt loads (parse needs no connection).
RUN cd /work/transform && dbt parse --quiet && test -f target/manifest.json

WORKDIR /work/transform
CMD ["dbt", "--help"]
