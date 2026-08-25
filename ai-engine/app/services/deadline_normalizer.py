import calendar
import re
import unicodedata
from datetime import date, timedelta


_EXPLICIT_DATE_PATTERNS = (
    re.compile(r"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"),
    re.compile(r"(?<!\d)(\d{4})/(\d{1,2})/(\d{1,2})(?!\d)"),
    re.compile(r"(?<!\d)(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日"),
)
_RELATIVE_YEAR_PATTERN = re.compile(
    r"(?P<relation>今年|明年)\s*(?P<month>\d{1,2})\s*月\s*"
    r"(?P<day>\d{1,2})\s*日"
)
_DAY_RELATIVE_PATTERN = re.compile(r"(?<![大])(?P<label>今天|明天|后天)")
_WEEKDAY_PATTERN = re.compile(r"(?<![本下])(?P<prefix>本周|下周)(?P<weekday>[一二三四五六日])")
_MONTH_END_PATTERN = re.compile(r"(?P<label>下月底|本月底|(?<![本下])月底)")
_FUZZY_SUPPORTED_EXPRESSION = re.compile(
    r"(?:今天|明天|后天|本周[一二三四五六日]|下周[一二三四五六日]|本月底|下月底|月底)"
    r"\s*(?:左右|前后)"
)

_DAY_OFFSETS = {"今天": 0, "明天": 1, "后天": 2}
_WEEKDAY_OFFSETS = {
    "一": 0,
    "二": 1,
    "三": 2,
    "四": 3,
    "五": 4,
    "六": 5,
    "日": 6,
}


class DeadlineNormalizer:
    """只根据 deadlineText 和显式参考日期做确定性日期运算。"""

    def normalize(
        self,
        deadline_text: str,
        reference_date: date | None,
    ) -> date | None:
        """返回唯一可证明的日期；不读取系统时钟，也不猜测缺失年份。"""

        normalized_text = unicodedata.normalize("NFKC", deadline_text).strip()
        if not normalized_text:
            return None

        explicit_dates, explicit_expression_seen, invalid_explicit_date = (
            self._extract_explicit_dates(normalized_text)
        )
        if explicit_expression_seen:
            if invalid_explicit_date or len(explicit_dates) != 1:
                return None
            return next(iter(explicit_dates))

        if reference_date is None:
            # 相对日期只能绑定 Source 的稳定上下文，绝不能回退到 date.today()。
            return None
        if _FUZZY_SUPPORTED_EXPRESSION.search(normalized_text):
            return None
        if "下下周" in normalized_text or "大后天" in normalized_text:
            return None

        resolved_dates: list[date | None] = []
        resolved_dates.extend(
            self._resolve_relative_year(match, reference_date)
            for match in _RELATIVE_YEAR_PATTERN.finditer(normalized_text)
        )
        resolved_dates.extend(
            reference_date + timedelta(days=_DAY_OFFSETS[match.group("label")])
            for match in _DAY_RELATIVE_PATTERN.finditer(normalized_text)
        )
        resolved_dates.extend(
            self._resolve_weekday(match, reference_date)
            for match in _WEEKDAY_PATTERN.finditer(normalized_text)
        )
        resolved_dates.extend(
            self._resolve_month_end(match.group("label"), reference_date)
            for match in _MONTH_END_PATTERN.finditer(normalized_text)
        )

        # 同一表达里出现多个可选日期或任何无效日期时仍保持不确定，不自行挑选。
        if len(resolved_dates) != 1 or resolved_dates[0] is None:
            return None
        return resolved_dates[0]

    def _extract_explicit_dates(
        self,
        deadline_text: str,
    ) -> tuple[set[date], bool, bool]:
        normalized_dates: set[date] = set()
        expression_seen = False
        invalid_date = False
        for pattern in _EXPLICIT_DATE_PATTERNS:
            for match in pattern.finditer(deadline_text):
                expression_seen = True
                try:
                    normalized_dates.add(
                        date(
                            int(match.group(1)),
                            int(match.group(2)),
                            int(match.group(3)),
                        )
                    )
                except ValueError:
                    # 无效显式日期必须保留原文并返回 null，不能自动滚动到其他日期。
                    invalid_date = True
        return normalized_dates, expression_seen, invalid_date

    def _resolve_relative_year(
        self,
        match: re.Match[str],
        reference_date: date,
    ) -> date | None:
        year = reference_date.year + (1 if match.group("relation") == "明年" else 0)
        try:
            return date(year, int(match.group("month")), int(match.group("day")))
        except ValueError:
            return None

    def _resolve_weekday(
        self,
        match: re.Match[str],
        reference_date: date,
    ) -> date:
        # Python weekday 固定 Monday=0，显式实现 ISO Monday→Sunday，不依赖系统 Locale。
        week_start = reference_date - timedelta(days=reference_date.weekday())
        week_offset = 7 if match.group("prefix") == "下周" else 0
        return week_start + timedelta(
            days=week_offset + _WEEKDAY_OFFSETS[match.group("weekday")]
        )

    def _resolve_month_end(self, label: str, reference_date: date) -> date:
        target_year = reference_date.year
        target_month = reference_date.month
        if label == "下月底":
            target_month += 1
            if target_month == 13:
                target_month = 1
                target_year += 1
        last_day = calendar.monthrange(target_year, target_month)[1]
        return date(target_year, target_month, last_day)
