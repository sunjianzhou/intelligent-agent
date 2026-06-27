from pydantic_settings import BaseSettings
from pydantic import ConfigDict
from typing import Optional
from pathlib import Path

# 项目根目录（agent/ 目录）固定计算，不依赖运行目录
_AGENT_ROOT = Path(__file__).parent.parent

class Settings(BaseSettings):
    """应用配置"""

    # 基础配置
    app_name: str = "Intelligent Agent"
    app_version: str = "0.1.0"
    debug: bool = False
    log_level: str = "INFO"

    # Ollama配置
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:7b"
    ollama_timeout: int = 600

    # 记忆存储
    memory_type: str = "chroma"  # chroma, sqlite, postgres
    chroma_persist_dir: str = str(_AGENT_ROOT / "chroma-data")
    long_term_persist_dir: str = str(_AGENT_ROOT / "chroma-data-longterm")
    vector_dimension: int = 384

    use_lightweight_embedding: bool = True  # 是否使用轻量级嵌入模型
    embedding_model: str = "all-MiniLM-L6-v2"  # 如果不使用轻量级，使用哪个模型

    # 定时任务
    scheduler_timezone: str = "Asia/Shanghai"
    scheduler_max_instances: int = 3

    # 网页搜索
    duckduckgo_max_results: int = 5
    search_timeout: int = 10

    # API服务器
    api_host: str = "127.0.0.1"
    api_port: int = 8000
    api_workers: int = 1
    # 推理并发控制（0 = 不限制）
    inference_concurrency: int = 3   # 同时执行推理的最大请求数
    inference_queue_size: int = 20   # 等待队列最大深度（超出返回 503）

    # L1 精确响应缓存
    response_cache_max_size: int = 500   # 最多缓存条数（LRU 淘汰）
    response_cache_ttl_secs: int = 3600  # 缓存有效期（秒）

    # L2 语义响应缓存（ChromaDB response_cache collection）
    semantic_cache_threshold: float = 0.92  # 余弦相似度命中阈值
    semantic_cache_ttl_secs: int = 86400    # 缓存有效期（秒），默认 24h
    semantic_cache_max_entries: int = 2000  # 最大条目数

    # 调度器任务并发控制
    scheduler_max_concurrent_tasks: int = 5  # 同时执行的最大任务数

    # 项目路径
    project_root: Path = Path(__file__).parent.parent.parent

    # 灵魂层配置
    soul_dir: Optional[Path] = None  # None = 使用 SoulLoader 默认路径（项目根/soul/）

    # 对话历史配置
    conversation_max_messages: int = 200  # 单会话最大消息条数（超出截断最旧的）

    # 短期记忆配置
    short_term_max_size: int = 100
    short_term_ttl_hours: int = 24

    # 长期记忆配置
    long_term_vector_db_type: str = "chroma"

    # 模型参数配置
    ollama_temperature: float = 0.7
    ollama_max_tokens: int = 2048
    ollama_top_p: float = 0.9
    ollama_top_k: int = 40
    ollama_repeat_penalty: float = 1.1
    ollama_num_ctx: int = 4096
    # GPU 层数：-1=Ollama 自动；正整数 N=只把 N 层放到 GPU，其余 layers 在 CPU 跑
    # 用于显存 < 模型大小的场景。例：dolphin 16GB F16 + GTX1660 6GB 建议 ollama_num_gpu=18
    ollama_num_gpu: int = -1
    # 模型在显存中的常驻时长：Ollama 默认空闲 5 分钟卸载模型，大模型重新加载需几秒到几十秒。
    # "-1"=永久常驻，"30m"=30分钟，"0"=用完即卸载。可通过 OLLAMA_KEEP_ALIVE 环境变量覆盖
    ollama_keep_alive: str = "30m"

    # 工具结果注入到上下文时的字符上限：超过则自动截断并附说明
    tool_result_max_chars: int = 3000

    # 请求超时（秒）；CPU 推理大模型（dolphin 16GB）需 200-300s，设 300 避免多次重试
    chat_timeout: int = 300
    # 上下文 token 预算上限（与 ollama_num_ctx 配合）
    # 留 1000+ token 余量给模型生成输出；可通过 MAX_CONTEXT_TOKENS 环境变量覆盖
    max_context_tokens: int = 7000

    # 意图过滤
    intent_use_embedding: bool = True       # 关键词未命中时是否回退到嵌入相似度
    intent_embedding_threshold: float = 0.30  # 嵌入相似度阈值（>= 才认为匹配）
    intent_embedding_top_k: int = 3           # 嵌入兜底时最多取 top-K 个分类

    # GitHub MCP 配置
    github_token: str = ""
    github_mcp_enabled: bool = False

    # 文件系统 MCP 配置（允许访问的目录，逗号分隔；通过 FILESYSTEM_ALLOWED_DIRS 环境变量配置）
    filesystem_mcp_enabled: bool = False
    filesystem_allowed_dirs: str = ""

    # JWT 鉴权（与 Java 后端必须使用相同的密钥）
    # 本地开发默认值与 application.yml 保持一致；生产环境通过 JWT_SECRET 环境变量注入
    jwt_secret: str = "local-dev-only-change-in-production-must-be-32chars"
    jwt_enabled: bool = True

    # 角色配置
    active_persona: str = "default"   # 默认角色名（对应 agent/personas/<name>.md）

    # 自动记忆提炼（9.3）
    memory_distill_interval: int = 5        # 每 N 轮对话触发一次提炼
    memory_distill_dedup_threshold: float = 0.85  # 与已有事实的相似度去重阈值

    # 阶段性摘要（9.4）
    memory_summary_interval: int = 10       # 每 N 轮对话生成一次阶段摘要

    # 不支持 Ollama 原生 Function Calling 的模型前缀（逗号分隔）。
    # 匹配这些前缀的模型自动切换到文本解析模式（<tool_call> 格式）。
    # 可通过 TEXT_TOOL_CALLING_PATTERNS 环境变量扩展，无需改源码。
    text_tool_calling_patterns: str = "dolphin,phi2,orca-mini,orca2"

    # 云端模型配置（留空则不启用）
    cloud_provider: str = ""  # openai / dashscope / deepseek / zhipu
    cloud_api_key: str = ""
    cloud_base_url: str = ""
    cloud_model: str = ""

    # 数据库工具配置（留空则不启用 DatabaseTool）
    db_type: str = ""        # mysql（目前仅支持 MySQL/OceanBase）
    db_host: str = ""
    db_port: int = 3306
    db_user: str = ""
    db_password: str = ""
    db_database: str = ""
    db_charset: str = "utf8mb4"

    # ── 图片生成配置（本地优先）────────────────────────────────────────────────
    # image_gen_provider 可选值：
    #   sd_webui   — AUTOMATIC1111 SD WebUI（本地，推荐）
    #   comfyui    — ComfyUI（本地，工作流驱动）
    #   diffusers  — HuggingFace diffusers 直接加载（本地，无需外部服务）
    #   siliconflow — 云端（需 API Key）
    image_gen_provider: str = "comfyui"
    image_gen_api_key: str = ""            # 仅云端 provider 使用
    image_gen_base_url: str = "http://localhost:8188"   # comfyui 默认端口；sd_webui 用 7860
    image_gen_model: str = ""              # 留空=使用服务当前加载的模型
    image_gen_size: str = "512x512"        # 本地算力有限，默认较小
    image_gen_steps: int = 20
    image_gen_sd_user: str = ""            # SD WebUI --api-auth 用户名（可选）
    image_gen_sd_pass: str = ""            # SD WebUI --api-auth 密码（可选）
    image_gen_output_dir: str = ""         # 留空=自动用 data/images 目录
    # ComfyUI 专属
    image_gen_comfyui_workflow: str = ""   # workflow JSON 文件路径（留空=内置默认流程）
    # diffusers 专属
    image_gen_diffusers_model: str = "runwayml/stable-diffusion-v1-5"
    image_gen_diffusers_device: str = "auto"   # auto | cuda | cpu | mps

    # ── 飞书IM 相关配置 ────────────────────────────────────────────────────────
    # 飞书 OAuth 用户授权
    feishu_app_id: str = ""                  # 已绑定 FEISHU_APP_ID 环境变量
    feishu_app_secret: str = ""              # 已绑定 FEISHU_APP_SECRET 环境变量
    feishu_oauth_redirect_uri: str = ""      # 公网 callback URL（Cloudflare Tunnel 域名）
    feishu_oauth_encryption_key: str = ""    # Fernet 密钥，python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

    model_config = ConfigDict(env_file=".env", case_sensitive=False, extra="ignore")


# 创建全局配置实例
settings = Settings()