import math
import os
from dataclasses import dataclass
from urllib.parse import urlparse


class LlmConfigurationError(RuntimeError):
    """LLM 环境配置缺失或不合法。"""


@dataclass(frozen=True)
class LlmSettings:
    api_key: str
    model: str
    base_url: str
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "LlmSettings":
        """按调用时读取配置，让缺少 Key 时健康检查仍能独立工作。"""

        api_key = _required_environment_value("LIFEINBOX_LLM_API_KEY")
        model = _required_environment_value("LIFEINBOX_LLM_MODEL")
        base_url = _required_environment_value("LIFEINBOX_LLM_BASE_URL").rstrip("/")

        parsed_url = urlparse(base_url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            raise LlmConfigurationError("LIFEINBOX_LLM_BASE_URL 必须是合法的 HTTP(S) 地址")

        timeout_text = os.getenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "20").strip()
        try:
            timeout_seconds = float(timeout_text)
        except ValueError as exception:
            raise LlmConfigurationError("LIFEINBOX_LLM_TIMEOUT_SECONDS 必须是数字") from exception
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0 or timeout_seconds > 300:
            raise LlmConfigurationError("LIFEINBOX_LLM_TIMEOUT_SECONDS 必须在 0 到 300 秒之间")

        return cls(
            api_key=api_key,
            model=model,
            base_url=base_url,
            timeout_seconds=timeout_seconds,
        )


def _required_environment_value(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        # API Key 只从运行环境读取，错误中不包含任何配置值。
        raise LlmConfigurationError(f"缺少 LLM 环境变量：{name}")
    return value
