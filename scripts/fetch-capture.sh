#!/usr/bin/env bash
# Milestone 1 helper: download one capture of every CSV resource that the UR
# organisation publishes on data.gov.lv, plus the CKAN metadata, into
#   data/raw/date=YYYY-MM-DD/
# Runs inside the duckdb container:  make fetch-capture
# data/ is git-ignored: it contains personal data.
set -euo pipefail

API="https://data.gov.lv/dati/api/3/action"
DATE="${1:-$(date -u +%F)}"
OUT="data/raw/date=${DATE}"
mkdir -p "${OUT}/metadata"

curl -fsS "${API}/package_search?fq=organization:ur&rows=200" -o "${OUT}/metadata/ckan_packages.json"

jq -r '.result.results[] | .resources[] | select((.format|ascii_upcase)=="CSV" or (.format|ascii_upcase)=="JSON") | .url' \
  "${OUT}/metadata/ckan_packages.json" | sort -u |
while read -r url; do
  name="$(basename "${url}")"
  case "${name}" in *.json) dest="${OUT}/metadata/${name}" ;; *) dest="${OUT}/${name}" ;; esac
  if [[ -s "${dest}" ]]; then echo "skip ${name}"; continue; fi
  echo "get  ${name}"
  curl -fsSL --retry 3 -D "${dest}.headers" -o "${dest}.part" "${url}" && mv "${dest}.part" "${dest}"
done

( cd "${OUT}" && sha256sum *.csv > SHA256SUMS )
echo "done: ${OUT}"
