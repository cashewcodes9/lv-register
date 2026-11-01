# Orchestration image: dbt (Milestone 6), Dagster joins in Milestone 7 (PLAN.md section 6.1).
FROM python:3.12.16-slim-bookworm

ARG HOST_UID=1000
ARG HOST_GID=1000

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1 \
    DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions
COPY requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt \
 && mkdir -p /opt/duckdb/extensions \
 && python -c "import duckdb; c = duckdb.connect(config={'extension_directory': '/opt/duckdb/extensions'}); c.sql('INSTALL httpfs'); c.sql('INSTALL json')" \
 && (getent group "${HOST_GID}" || groupadd --gid "${HOST_GID}" lvregister) \
 && useradd --create-home --uid "${HOST_UID}" --gid "${HOST_GID}" lvregister

USER lvregister
WORKDIR /work/transform
ENTRYPOINT ["dbt"]
CMD ["--help"]
