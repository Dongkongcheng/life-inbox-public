from collections.abc import Callable
from datetime import date
from typing import Any

import httpx

from app.config import LlmSettings
from app.prompts import (
    ACTION_EXTRACTION_SYSTEM_PROMPT,
    ANALYZE_SYSTEM_PROMPT,
    RELATION_DISCOVERY_SYSTEM_PROMPT,
    build_action_extraction_user_prompt,
    build_analyze_user_prompt,
    build_relation_discovery_user_prompt,
)
from app.schemas.relation_discovery import (
    RelationDiscoveryCandidate,
    RelationDiscoverySource,
)


class LlmServiceError(RuntimeError):
    """LLM 网络、鉴权或上游状态异常。"""


class LlmTimeoutError(LlmServiceError):
    """LLM 在配置的时间内没有返回。"""


class LlmInvalidResponseError(LlmServiceError):
    """LLM 返回的外层响应或结构化 JSON 不合法。"""


class LlmClient:
    """调用可配置的 OpenAI-compatible Chat Completions API。"""

    def __init__(
        self,
        settings_loader: Callable[[], LlmSettings] = LlmSettings.from_environment,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._settings_loader = settings_loader
        self._transport = transport

    def generate_analysis(self, title: str | None, text: str) -> str:
        return self._generate_json(
            ANALYZE_SYSTEM_PROMPT,
            build_analyze_user_prompt(title, text),
        )

    def generate_action_extraction(
        self,
        text: str,
        reference_date: date | None = None,
    ) -> str:
        """Action 与 Analyze 复用同一 Provider、配置和安全错误边界。"""

        return self._generate_json(
            ACTION_EXTRACTION_SYSTEM_PROMPT,
            build_action_extraction_user_prompt(text, reference_date),
        )

    def generate_relation_discovery(
        self,
        source: RelationDiscoverySource,
        candidates: list[RelationDiscoveryCandidate],
    ) -> str:
        """Relation 复用通用 Provider，并以一次请求批量判断全部候选。"""

        return self._generate_json(
            RELATION_DISCOVERY_SYSTEM_PROMPT,
            build_relation_discovery_user_prompt(source, candidates),
        )

    def _generate_json(self, system_prompt: str, user_prompt: str) -> str:
        settings = self._settings_loader()
        request_body: dict[str, Any] = {
            "model": settings.model,
            # 当前能力都只做结构化抽取；关闭思考可减少等待和 Token 消耗。
            "enable_thinking": False,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            # 千问等 OpenAI-compatible 服务用 JSON Mode 保证输出可直接解析。
            "response_format": {"type": "json_object"},
        }

        try:
            # 不使用供应商 SDK，Base URL、Model 和 Key 均由环境变量决定。
            with httpx.Client(
                timeout=httpx.Timeout(settings.timeout_seconds),
                transport=self._transport,
            ) as client:
                response = client.post(
                    f"{settings.base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {settings.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=request_body,
                )
                response.raise_for_status()
        except httpx.TimeoutException as exception:
            raise LlmTimeoutError("LLM 请求超时") from exception
        except (httpx.RequestError, httpx.HTTPStatusError) as exception:
            # 不把上游响应正文或 API Key 暴露给调用方。
            raise LlmServiceError("LLM 服务暂不可用") from exception

        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exception:
            raise LlmInvalidResponseError("LLM 返回结构不合法") from exception
        if not isinstance(content, str) or not content.strip():
            raise LlmInvalidResponseError("LLM 返回了空结构化结果")
        return content.strip()
