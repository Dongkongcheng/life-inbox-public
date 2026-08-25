from datetime import date

import pytest

from app.services.deadline_normalizer import DeadlineNormalizer


normalizer = DeadlineNormalizer()


@pytest.mark.parametrize(
    ("deadline_text", "expected"),
    [
        ("2026年8月25日前", date(2026, 8, 25)),
        ("截止日期为2026-08-25", date(2026, 8, 25)),
        ("2026/08/25前完成", date(2026, 8, 25)),
    ],
    ids=["chinese", "iso", "slash"],
)
def test_full_explicit_date_does_not_need_reference_date(
    deadline_text: str,
    expected: date,
) -> None:
    assert normalizer.normalize(deadline_text, None) == expected


@pytest.mark.parametrize(
    ("deadline_text", "expected"),
    [
        ("今天", date(2026, 8, 24)),
        ("明天之前", date(2026, 8, 25)),
        ("后天提交", date(2026, 8, 26)),
        ("明天下午3点", date(2026, 8, 25)),
        ("本周一", date(2026, 8, 24)),
        ("本周五", date(2026, 8, 28)),
        ("本周日", date(2026, 8, 30)),
        ("下周一", date(2026, 8, 31)),
        ("下周五", date(2026, 9, 4)),
        ("下周日", date(2026, 9, 6)),
        ("本月底之前", date(2026, 8, 31)),
        ("月底前", date(2026, 8, 31)),
        ("下月底前", date(2026, 9, 30)),
        ("今年9月10日前", date(2026, 9, 10)),
        ("明年1月10日前", date(2027, 1, 10)),
    ],
)
def test_supported_relative_dates_are_deterministic(
    deadline_text: str,
    expected: date,
) -> None:
    assert normalizer.normalize(deadline_text, date(2026, 8, 24)) == expected


@pytest.mark.parametrize(
    ("reference_date", "expected"),
    [
        (date(2026, 2, 10), date(2026, 2, 28)),
        (date(2028, 2, 10), date(2028, 2, 29)),
        (date(2026, 4, 10), date(2026, 4, 30)),
        (date(2026, 8, 10), date(2026, 8, 31)),
    ],
    ids=["common-february", "leap-february", "april", "august"],
)
def test_month_end_uses_standard_calendar_rules(
    reference_date: date,
    expected: date,
) -> None:
    assert normalizer.normalize("本月底", reference_date) == expected


@pytest.mark.parametrize(
    "deadline_text",
    [
        "8月25日前",
        "周五之前",
        "下下周五",
        "大后天",
        "明天或后天",
        "月底左右",
        "尽快",
        "过几天",
        "Q4之前",
    ],
)
def test_ambiguous_or_unsupported_expressions_remain_unresolved(
    deadline_text: str,
) -> None:
    assert normalizer.normalize(deadline_text, date(2026, 8, 24)) is None


def test_relative_date_without_reference_date_never_uses_runtime_date() -> None:
    assert normalizer.normalize("明天", None) is None


@pytest.mark.parametrize(
    "deadline_text",
    [
        "2026年2月31日",
        "2026-02-29",
        "今年2月29日",
    ],
)
def test_invalid_explicit_or_relative_year_date_is_not_corrected(
    deadline_text: str,
) -> None:
    assert normalizer.normalize(deadline_text, date(2026, 8, 24)) is None


def test_valid_leap_day_is_preserved() -> None:
    assert normalizer.normalize("2028-02-29", None) == date(2028, 2, 29)


@pytest.mark.parametrize(
    ("reference_date", "deadline_text", "expected"),
    [
        (date(2026, 12, 31), "明天", date(2027, 1, 1)),
        (date(2026, 8, 31), "明天", date(2026, 9, 1)),
        (date(2026, 12, 31), "下周一", date(2027, 1, 4)),
        (date(2026, 12, 15), "下月底", date(2027, 1, 31)),
    ],
)
def test_month_and_year_boundaries_use_date_arithmetic(
    reference_date: date,
    deadline_text: str,
    expected: date,
) -> None:
    assert normalizer.normalize(deadline_text, reference_date) == expected
