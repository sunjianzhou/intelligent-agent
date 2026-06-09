"""常量定义"""

# 工具类型
TOOL_TYPES = {
    "SEARCH": "search",
    "SHELL": "shell",
    "CALCULATE": "calculate",
    "FILE": "file",
    "WEB": "web",
    "CUSTOM": "custom"
}

# 任务状态
TASK_STATUS = {
    "PENDING": "pending",
    "RUNNING": "running",
    "COMPLETED": "completed",
    "FAILED": "failed",
    "CANCELLED": "cancelled"
}

# 记忆类型
MEMORY_TYPES = {
    "SHORT_TERM": "short_term",
    "LONG_TERM": "long_term",
    "EMBEDDING": "embedding"
}

# Ollama模型预设
MODEL_PRESETS = {
    "DOLPHIN": "dolphin",
    "LLAMA3": "llama3",
    "MISTRAL": "mistral",
    "NEURAL_CHAT": "neural-chat"
}