import math
import os
import re
from dataclasses import dataclass
from urllib.parse import urlparse


class LlmConfigurationError(RuntimeError):
    """LLM 环境配置缺失或不合法。"""


class EmbeddingConfigurationError(RuntimeError):
    """Embedding 环境配置缺失或不合法；不影响其他 AI 能力启动。"""


class VectorStoreConfigurationError(RuntimeError):
    """Qdrant 配置不合法；按请求惰性读取，不阻止既有 AI 能力启动。"""


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


@dataclass(frozen=True)
class EmbeddingSettings:
    api_key: str
    model: str
    base_url: str
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "EmbeddingSettings":
        """Embedding Model 独立配置，Provider 地址、密钥和超时复用现有 AI 配置。"""

        api_key = _required_embedding_environment_value("LIFEINBOX_LLM_API_KEY")
        model = _required_embedding_environment_value("LIFEINBOX_EMBEDDING_MODEL")
        base_url = _required_embedding_environment_value(
            "LIFEINBOX_LLM_BASE_URL"
        ).rstrip("/")

        parsed_url = urlparse(base_url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            raise EmbeddingConfigurationError(
                "LIFEINBOX_LLM_BASE_URL 必须是合法的 HTTP(S) 地址"
            )

        timeout_text = os.getenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "20").strip()
        try:
            timeout_seconds = float(timeout_text)
        except ValueError as exception:
            raise EmbeddingConfigurationError(
                "LIFEINBOX_LLM_TIMEOUT_SECONDS 必须是数字"
            ) from exception
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0 or timeout_seconds > 300:
            raise EmbeddingConfigurationError(
                "LIFEINBOX_LLM_TIMEOUT_SECONDS 必须在 0 到 300 秒之间"
            )

        return cls(
            api_key=api_key,
            model=model,
            base_url=base_url,
            timeout_seconds=timeout_seconds,
        )


@dataclass(frozen=True)
class VectorStoreSettings:
    enabled: bool
    url: str
    collection_prefix: str
    api_key: str | None
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "VectorStoreSettings":
        """Qdrant 默认关闭；所有连接配置只在索引或删除发生时读取。"""

        enabled_text = os.getenv("LIFEINBOX_VECTOR_STORE_ENABLED", "false").strip().lower()
        if enabled_text in {"true", "1"}:
            enabled = True
        elif enabled_text in {"false", "0", ""}:
            enabled = False
        else:
            raise VectorStoreConfigurationError(
                "LIFEINBOX_VECTOR_STORE_ENABLED 必须是 true 或 false"
            )

        if not enabled:
            # 关闭时不校验任何 Qdrant 连接参数，避免无关配置影响现有 Analyze/Embedding。
            return cls(
                enabled=False,
                url="http://127.0.0.1:6333",
                collection_prefix="lifeinbox_items",
                api_key=None,
                timeout_seconds=5,
            )

        url = os.getenv("LIFEINBOX_QDRANT_URL", "http://127.0.0.1:6333").strip().rstrip("/")
        parsed_url = urlparse(url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            raise VectorStoreConfigurationError(
                "LIFEINBOX_QDRANT_URL 必须是合法的 HTTP(S) 地址"
            )

        collection_prefix = os.getenv(
            "LIFEINBOX_QDRANT_COLLECTION", "lifeinbox_items"
        ).strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", collection_prefix):
            raise VectorStoreConfigurationError(
                "LIFEINBOX_QDRANT_COLLECTION 只能包含字母、数字、下划线或连字符"
            )

        timeout_text = os.getenv("LIFEINBOX_QDRANT_TIMEOUT_SECONDS", "5").strip()
        try:
            timeout_seconds = float(timeout_text)
        except ValueError as exception:
            raise VectorStoreConfigurationError(
                "LIFEINBOX_QDRANT_TIMEOUT_SECONDS 必须是数字"
            ) from exception
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0 or timeout_seconds > 300:
            raise VectorStoreConfigurationError(
                "LIFEINBOX_QDRANT_TIMEOUT_SECONDS 必须在 0 到 300 秒之间"
            )

        api_key = os.getenv("LIFEINBOX_QDRANT_API_KEY", "").strip() or None
        return cls(
            enabled=enabled,
            url=url,
            collection_prefix=collection_prefix,
            api_key=api_key,
            timeout_seconds=timeout_seconds,
        )


def _required_environment_value(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        # API Key 只从运行环境读取，错误中不包含任何配置值。
        raise LlmConfigurationError(f"缺少 LLM 环境变量：{name}")
    return value


def _required_embedding_environment_value(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        # 错误只包含变量名，绝不回显密钥或其他配置值。
        raise EmbeddingConfigurationError(f"缺少 Embedding 环境变量：{name}")
    return value
