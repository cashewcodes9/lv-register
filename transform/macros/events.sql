{#- The change events written by the Java diff step: one JSON Lines file per capture. Read
    with explicit columns, so the empty file of the baseline capture is fine. Not a dbt
    source, because a source location cannot hold the {...} column list. -#}
{% macro read_events() -%}
read_json('{{ env_var('LV_LAKE_ROOT', 's3://lv-lake') }}/events/*/events.jsonl', format = 'newline_delimited', hive_partitioning = false,
          columns = {'id': 'VARCHAR', 'type': 'VARCHAR', 'regNumber': 'VARCHAR', 'previous': 'DATE',
                     'current': 'DATE', 'payload': 'JSON'})
{%- endmacro %}
