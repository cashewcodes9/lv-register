"""One partition per capture actually made, keyed by its Riga date (YYYY-MM-DD)."""

import datetime as dt
from zoneinfo import ZoneInfo

import dagster as dg

CAPTURE_ZONE = ZoneInfo("Europe/Riga")

captures = dg.DynamicPartitionsDefinition(name="captures")


def capture_date(moment: dt.datetime) -> str:
    """The capture date of a moment: its date in Riga (docs/FAQ.md, weekly captures)."""
    return moment.astimezone(CAPTURE_ZONE).date().isoformat()
