"""Ollama服务调用"""
import time

import requests
import json
from typing import Dict, Any, Optional, List
import os
import sys

from loguru import logger
from config.settings import settings


class OllamaService:
    def __init__(self, base_url: str = "http://localhost:11434"):
        self.base_url = base_url

        # 尝试从配置读取模型
        try:
            self.model_config = settings.ollama_model
            logger.info(f"📦 配置的模型: {self.model_config}")
        except ImportError:
            self.model_config = "qwen2.5:7b"
            logger.warning(f"⚠️ 无法从配置读取模型，使用默认: {self.model_config}")

        # 获取可用模型并尝试匹配
        self.available_models = self.get_models_list()
        self.model = self.find_best_model_match()
        logger.info(f"current model: {self.model}")

    def get_models_list(self) -> List[str]:
        """获取可用的模型列表"""
        try:
            response = requests.get(f"{self.base_url}/api/tags", timeout=5)
            if response.status_code == 200:
                data = response.json()
                models = []
                for model_info in data.get("models", []):
                    model_name = model_info.get("name", "")
                    models.append(model_name)
                logger.info(f"📊 可用模型列表: {models}")
                return models
            else:
                logger.warning(f"❌ 获取模型列表失败: {response.status_code}")
                return []
        except Exception as e:
            logger.error(f"❌ 连接Ollama失败: {e}")
            return []

    def find_best_model_match(self) -> str:
        """根据配置找到最佳匹配的模型"""
        if not self.available_models:
            logger.warning("⚠️ 没有可用模型，使用配置的默认模型")
            return self.model_config

        # 1. 完全匹配
        if self.model_config in self.available_models:
            logger.info(f"✅ 找到完全匹配的模型: {self.model_config}")
            return self.model_config

        # 2. 尝试匹配（包含版本号）
        for model in self.available_models:
            if model == self.model_config or model.startswith(f"{self.model_config}:"):
                logger.info(f"✅ 找到匹配的模型: {model}")
                return model

        # 3. 如果没有匹配，使用第一个可用模型
        logger.warning(f"⚠️ 未找到匹配的模型 '{self.model_config}'，使用第一个可用模型: {self.available_models[0]}")
        return self.available_models[0]

    def check_connection(self) -> bool:
        """检查Ollama服务是否可用"""
        try:
            response = requests.get(f"{self.base_url}/api/tags", timeout=5)
            if response.status_code == 200:
                data = response.json()
                models = [model.get("name") for model in data.get("models", [])]
                logger.info(f"可用模型: {models}")
                return any(m == self.model or m.startswith(f"{self.model}:") for m in models)
        except Exception as e:
            logger.error(f"连接Ollama失败: {e}")
        return False

    def chat(self, message: str, context: Optional[list] = None) -> Dict[str, Any]:
        """调用Ollama API进行聊天"""
        url = f"{self.base_url}/api/generate"

        # 构建请求体
        payload = {
            "model": self.model,
            "prompt": message,
            "stream": False
        }
        # 从配置中读取模型参数
        options = {
            "temperature": settings.ollama_temperature,
            "num_predict": settings.ollama_max_tokens,
            "top_p": settings.ollama_top_p,
            "top_k": settings.ollama_top_k,
            "repeat_penalty": settings.ollama_repeat_penalty,
            "num_ctx": settings.ollama_num_ctx
        }
        payload["options"] = options

        # 添加上下文（如果提供）
        if context:
            payload["context"] = context

        try:
            logger.info(f"调用Ollama: {message[:50]}...")
            response = requests.post(
                url,
                json=payload,
                headers={"Content-Type": "application/json"},
                timeout=30
            )

            if response.status_code == 200:
                data = response.json()
                response_text = data.get("response", "")
                context = data.get("context")

                return {
                    "response": response_text,
                    "context": context,
                    "total_duration": data.get("total_duration", 0),
                    "load_duration": data.get("load_duration", 0),
                    "prompt_eval_count": data.get("prompt_eval_count", 0),
                    "eval_count": data.get("eval_count", 0)
                }
            else:
                logger.error(f"Ollama API错误: {response.status_code}")
                return {
                    "response": f"Ollama服务错误: {response.status_code}",
                    "context": context
                }

        except requests.exceptions.Timeout:
            logger.error("Ollama请求超时")
            return {
                "response": "Ollama请求超时，请检查模型是否运行",
                "context": context
            }
        except Exception as e:
            logger.error(f"调用Ollama失败: {e}")
            return {
                "response": f"调用Ollama失败: {str(e)}",
                "context": context
            }

    def get_models(self) -> list:
        """获取可用模型列表"""
        try:
            response = requests.get(f"{self.base_url}/api/tags", timeout=5)
            if response.status_code == 200:
                data = response.json()
                return data.get("models", [])
        except Exception as e:
            logger.error(f"获取模型列表失败: {e}")
        return []

    def chat_with_retry(self, message: str, context: Optional[list] = None, max_retries: int = 2) -> Dict[str, Any]:
        """带重试机制的聊天调用"""
        for attempt in range(max_retries + 1):
            try:
                result = self.chat(message, context)
                if "Ollama服务错误" not in result["response"] and "调用Ollama失败" not in result["response"]:
                    return result

                logger.warning(f"第{attempt + 1}次重试，原因: {result['response'][:100]}")
                time.sleep(1)  # 等待1秒后重试

            except Exception as e:
                logger.error(f"聊天调用失败 (尝试{attempt + 1}): {e}")
                if attempt == max_retries:
                    return {
                        "response": f"聊天服务暂时不可用: {str(e)}",
                        "context": context
                    }

        return {
            "response": "聊天服务暂时不可用，请稍后重试",
            "context": context
        }