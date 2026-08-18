from pydantic import BaseModel, ConfigDict

from app.schemas.analyze import (
    MAX_ANALYZE_INPUT_CHARS,
    MAX_ANALYZE_SUMMARY_CHARS,
    AnalyzeRequest,
    SummaryText,
)


# 保留旧常量名，避免兼容入口和已有测试在本次小步迁移中突然失效。
MAX_SUMMARY_INPUT_CHARS = MAX_ANALYZE_INPUT_CHARS
MAX_SUMMARY_OUTPUT_CHARS = MAX_ANALYZE_SUMMARY_CHARS


class SummaryRequest(AnalyzeRequest):
    """旧 /summarize 请求继续沿用与 Analyze 相同的 TEXT 输入规则。"""


class SummaryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: SummaryText
