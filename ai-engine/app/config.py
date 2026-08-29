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


class RerankConfigurationError(RuntimeError):
    """Rerank 配置缺失或不合法；该增强能力不阻止 FastAPI 启动。"""


@dataclass(frozen=True)
class LlmSettings:
    api_key: str
    model: str
    base_url: str
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "LlmSettings":
        """按调用时读取配置，让缺少 Key 时健康检查仍能独立工作。"""

        api_key = _required_environment_value(
            "LIFEINBOX_LLM_API_KEY",
            LlmConfigurationError,
            "LLM",
        )
        model = _required_environment_value(
            "LIFEINBOX_LLM_MODEL",
            LlmConfigurationError,
            "LLM",
        )
        base_url = _http_url_environment_value(
            "LIFEINBOX_LLM_BASE_URL",
            _required_environment_value(
                "LIFEINBOX_LLM_BASE_URL",
                LlmConfigurationError,
                "LLM",
            ),
            LlmConfigurationError,
        )
        timeout_seconds = _timeout_environment_value(
            "LIFEINBOX_LLM_TIMEOUT_SECONDS",
            "20",
            300,
            LlmConfigurationError,
        )

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
    def model_from_environment(cls) -> str:
        """只解析当前模型标识，供已有向量定位使用，不要求调用 Embedding Provider。"""

        return _required_environment_value(
            "LIFEINBOX_EMBEDDING_MODEL",
            EmbeddingConfigurationError,
            "Embedding",
        )

    @classmethod
    def from_environment(cls) -> "EmbeddingSettings":
        """Embedding Model 独立配置，Provider 地址、密钥和超时复用现有 AI 配置。"""

        api_key = _required_environment_value(
            "LIFEINBOX_LLM_API_KEY",
            EmbeddingConfigurationError,
            "Embedding",
        )
        model = cls.model_from_environment()
        base_url = _http_url_environment_value(
            "LIFEINBOX_LLM_BASE_URL",
            _required_environment_value(
                "LIFEINBOX_LLM_BASE_URL",
                EmbeddingConfigurationError,
                "Embedding",
            ),
            EmbeddingConfigurationError,
        )
        timeout_seconds = _timeout_environment_value(
            "LIFEINBOX_LLM_TIMEOUT_SECONDS",
            "20",
            300,
            EmbeddingConfigurationError,
        )

        return cls(
            api_key=api_key,
            model=model,
            base_url=base_url,
            timeout_seconds=timeout_seconds,
        )


@dataclass(frozen=True)
class RerankSettings:
    api_key: str
    model: str
    base_url: str
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "RerankSettings":
        """Rerank 使用独立 Provider 地址，并安全复用当前 Workspace 密钥。"""

        api_key = _required_environment_value(
            "LIFEINBOX_LLM_API_KEY",
            RerankConfigurationError,
            "Rerank",
        )
        model = _required_environment_value(
            "LIFEINBOX_RERANK_MODEL",
            RerankConfigurationError,
            "Rerank",
        )
        base_url = _http_url_environment_value(
            "LIFEINBOX_RERANK_BASE_URL",
            _required_environment_value(
                "LIFEINBOX_RERANK_BASE_URL",
                RerankConfigurationError,
                "Rerank",
            ),
            RerankConfigurationError,
        )
        timeout_seconds = _timeout_environment_value(
            "LIFEINBOX_RERANK_TIMEOUT_SECONDS",
            "8",
            60,
            RerankConfigurationError,
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

        url = _http_url_environment_value(
            "LIFEINBOX_QDRANT_URL",
            os.getenv("LIFEINBOX_QDRANT_URL", "http://127.0.0.1:6333").strip(),
            VectorStoreConfigurationError,
        )

        collection_prefix = os.getenv(
            "LIFEINBOX_QDRANT_COLLECTION", "lifeinbox_items"
        ).strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", collection_prefix):
            raise VectorStoreConfigurationError(
                "LIFEINBOX_QDRANT_COLLECTION 只能包含字母、数字、下划线或连字符"
            )

        timeout_seconds = _timeout_environment_value(
            "LIFEINBOX_QDRANT_TIMEOUT_SECONDS",
            "5",
            300,
            VectorStoreConfigurationError,
        )

        api_key = os.getenv("LIFEINBOX_QDRANT_API_KEY", "").strip() or None
        return cls(
            enabled=enabled,
            url=url,
            collection_prefix=collection_prefix,
            api_key=api_key,
            timeout_seconds=timeout_seconds,
        )


def _required_environment_value(
    name: str,
    error_type: type[RuntimeError],
    capability: str,
) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        # API Key 只从运行环境读取，错误中不包含任何配置值。
        raise error_type(f"缺少 {capability} 环境变量：{name}")
    return value


def _http_url_environment_value(
    name: str,
    value: str,
    error_type: type[RuntimeError],
) -> str:
    normalized = value.rstrip("/")
    parsed_url = urlparse(normalized)
    if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
        raise error_type(f"{name} 必须是合法的 HTTP(S) 地址")
    return normalized


def _timeout_environment_value(
    name: str,
    default: str,
    max_seconds: int,
    error_type: type[RuntimeError],
) -> float:
    timeout_text = os.getenv(name, default).strip()
    try:
        timeout_seconds = float(timeout_text)
    except ValueError as exception:
        raise error_type(f"{name} 必须是数字") from exception
    if (
        not math.isfinite(timeout_seconds)
        or timeout_seconds <= 0
        or timeout_seconds > max_seconds
    ):
        raise error_type(f"{name} 必须在 0 到 {max_seconds} 秒之间")
    return timeout_seconds
