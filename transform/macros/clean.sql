{#- Text normalization, the same as lvregister.normalize.Text: NFC, whitespace runs (with
    NBSP, U+2007, U+202F) collapsed to one space, trimmed; blank means NULL. -#}
{% macro clean_text(col) -%}
nullif(trim(regexp_replace(nfc_normalize({{ col }}), '[ \t\n\x0B\f\r\x{00A0}\x{2007}\x{202F}]+', ' ', 'g')), '')
{%- endmacro %}

{#- Codes such as SIA or BOARD_MEMBER: cleaned and upper case. -#}
{% macro clean_code(col) -%}
upper({{ clean_text(col) }})
{%- endmacro %}

{#- A registration number: 9 or 11 digits, leading zeros kept. NULL when malformed. -#}
{% macro reg_number(col) -%}
case when regexp_full_match({{ clean_text(col) }}, '\d{9}|\d{11}') then {{ clean_text(col) }} end
{%- endmacro %}

{#- An ISO date, NULL when missing or malformed (Java reads a malformed date as missing). -#}
{% macro clean_date(col) -%}
try_cast({{ clean_text(col) }} as date)
{%- endmacro %}

{#- A non-negative decimal as canonical text, so 100 and 100.00 compare equal. -#}
{% macro clean_decimal(col) -%}
case when try_cast({{ clean_text(col) }} as decimal(38, 10)) >= 0
     then cast(try_cast({{ clean_text(col) }} as decimal(38, 10)) as varchar) end
{%- endmacro %}
