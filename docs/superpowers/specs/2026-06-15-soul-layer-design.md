# 灵魂层基础设施设计文档

**日期**：2026-06-15  
**版本**：v1.0  
**作者**：Linmiaoshusheng  
**参考**：飞书 Aily 智能体"灵魂三件套"机制

---

## 背景

现有架构（ReAct 5 轮 + L1/L2 缓存 + God Class 4 Mixin + 任务调度 + 角色 personas/*.md 热加载）缺少"灵魂三件套"机制（SOUL/USER/MEMORY）驱动的 system prompt 构建。本阶段目标：落地 SoulLoader + SystemPromptBuilder，确保灵魂层统一驱动所有 system prompt 构建。

---

## 一、整体架构与数据流

### 目录结构

```
项目根目录 soul/
  ├── SOUL.md       ← 真话 / 边界 / 气质 / 主动防御
  ├── USER.md       ← 用户画像 / 称呼 / 学习体系
  ├── MEMORY.md     ← 精选记忆 / 事件日志 / 主题索引
  ├── IDENTITY.md   ← 名字 / 身份 / 风格 / 签名
  ├── HEARTBEAT.md  ← 推送前自检铁规延伸（注入 system prompt 自检段）
  └── whisper.md    ← 私密档案（可空，.gitignore 排除）

agent/soul/loader.py                ← SoulLoader 类
agent/core/system_prompt_builder.py ← SystemPromptBuilder 类（替换 PromptBuilder）
agent/tests/test_soul_loader.py     ← 12 个单元测试
agent/tests/test_system_prompt_builder.py ← 9 个单元测试
```

### 启动数据流

```
IntelligentAgent.__init__()
  ├── self.soul = SoulLoader(soul_dir=settings.soul_dir)
  │     └── 加载 6 文件，whisper 可选；打印"灵魂加载成功"
  └── self.prompt_builder = SystemPromptBuilder()
```

### 每次请求数据流

```
ConversationFlowMixin.system_prompt (property)
  ├── role_ctx  = _get_role_ctx_for_prompt(persona_content)
  ├── tool_ovl  = prompt_manager.get_overlay(model)
  └── return self.prompt_builder.build(
          soul=self.soul,
          role_ctx=role_ctx,
          tool_overlay=tool_ovl
      )
```

### System Prompt 拼装顺序（固定铁律）

```
① SOUL + IDENTITY  ← 铁律最前，防 Lost-in-Middle
② USER
③ MEMORY
④ HEARTBEAT        ← 自检铁规段
⑤ persona          ← 来自 role_ctx，无角色时跳过
⑥ whisper          ← 非空时追加
⑦ tool_overlay     ← 始终最后（独立字段，解耦 soul 层与工具层）
```

---

## 二、SoulLoader 设计

### 数据类

```python
@dataclass
class SoulData:
    soul: str       # SOUL.md 内容
    user: str       # USER.md 内容
    memory: str     # MEMORY.md 内容
    identity: str   # IDENTITY.md 内容
    heartbeat: str  # HEARTBEAT.md 内容——推送前自检铁规延伸，注入 system prompt
    whisper: str    # whisper.md 内容（可为空字符串）
```

### 类设计

```python
class SoulLoader:
    # 默认路径：loader.py → agent/soul/ → agent/ → 项目根 → soul/
    _DEFAULT_SOUL_DIR = Path(__file__).parent.parent.parent / "soul"

    REQUIRED = ["SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT"]
    OPTIONAL = ["whisper"]

    def __init__(self, soul_dir: Optional[str] = None):
        self._soul_dir = Path(soul_dir) if soul_dir else self._DEFAULT_SOUL_DIR
        self._data: Optional[SoulData] = None
        self.load()

    def load(self) -> SoulData:
        self._data = None          # 失败时状态明确
        # 读文件、构造 SoulData
        logger.info("灵魂加载成功")
        return self._data

    @property
    def data(self) -> SoulData:
        return self._data

    def reload(self) -> SoulData:  # 热重载入口
        return self.load()
```

### 关键决策

- `soul_dir` 以 `loader.py` 文件位置为锚点向上两级定位项目根，彻底不依赖 CWD
- `soul_dir` 支持通过 `settings.soul_dir` 覆盖（可配置路径）
- 5 个必选文件缺失 → 抛 `FileNotFoundError`（启动失败，快速发现配置问题）
- `whisper.md` 缺失 → 静默，`SoulData.whisper = ""`
- `load()` 既是构造器调用也是热重载入口，接口统一

---

## 三、SystemPromptBuilder 设计

```python
class SystemPromptBuilder:
    _SEP = "\n" + "─" * 60 + "\n"

    def build(
        self,
        soul: SoulLoader,
        role_ctx: Optional[Dict[str, Any]] = None,
        tool_overlay: str = "",
    ) -> str:
        d = soul.data
        if d is None:
            raise RuntimeError("soul not loaded")

        sections: list[str] = []
        sections.append(self._wrap("【灵魂核心】", d.soul))
        sections.append(self._wrap("【身份】", d.identity))
        sections.append(self._wrap("【用户画像】", d.user))
        sections.append(self._wrap("【精选记忆】", d.memory))
        sections.append(self._wrap("【自检铁规】", d.heartbeat))   # HEARTBEAT 注入
        if role_ctx:
            sections.append(self._build_persona(role_ctx))
        if d.whisper.strip():
            sections.append(self._wrap("【私密档案】", d.whisper))
        if tool_overlay.strip():
            sections.append(tool_overlay.strip())

        result = self._SEP.join(s for s in sections if s)
        logger.debug("final prompt: %d chars", len(result))
        return result

    def _build_persona(self, role_ctx: Dict[str, Any]) -> str:
        # 简化路径：role_ctx 仅含 persona_md 时直接返回
        if set(role_ctx.keys()) == {"persona_md"}:
            return role_ctx["persona_md"]
        # 完整路径：多角色接入（v4.7.8 决策 B·渐进式迁移）
        from ..personas.prompt_builder import PromptBuilder
        return PromptBuilder().build_system_prompt(role_ctx)

    def _wrap(self, header: str, content: str) -> str:
        return f"{header}\n{content.strip()}" if content.strip() else ""
```

---

## 四、PrePushGuard

**废止**。府邸 = 数据本机化（主动防御），不需要被动黑名单扫描。

---

## 五、集成变更

### `agent/__init__.py` 预导入

```python
import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from soul.loader import SoulLoader           # noqa: E402
from core.system_prompt_builder import SystemPromptBuilder  # noqa: E402
```

### `agent/core/agent.py` 追加 soul 初始化

```python
# 灵魂层（在 MemoryManager 之后）
self.soul = SoulLoader(soul_dir=getattr(settings, "soul_dir", None))
self.prompt_builder = SystemPromptBuilder()
```

### `agent/core/conversation_flow.py` 替换 `system_prompt` 属性

```python
@property
def system_prompt(self) -> str:
    _, eff_model = self._get_eff_provider()
    persona_content = self._get_eff_persona()
    role_ctx = self._get_role_ctx_for_prompt(persona_content)
    tool_overlay = prompt_manager.get_overlay(eff_model or "")
    return self.prompt_builder.build(
        soul=self.soul,
        role_ctx=role_ctx,
        tool_overlay=tool_overlay,
    )

def _get_role_ctx_for_prompt(self, persona_content):
    """有激活角色时返回 role_ctx，否则 None。多角色接入时扩展此方法。"""
    if not persona_content:
        return None
    return {"persona_md": persona_content}
```

### `agent/prompts/prompt_manager.py` 新增 `get_overlay()`

```python
def get_overlay(self, model_name: str = "") -> str:
    """只返回 overlay 字段，供 SystemPromptBuilder 独立注入。"""
    # 复用现有匹配逻辑，只取 data.get("overlay", "")
```

---

## 六、测试策略

### `agent/tests/test_soul_loader.py`（12 用例）

| # | 用例 | 覆盖点 |
|---|------|--------|
| 1 | 正常加载 6 文件 → `data` 非 None | 主路径 |
| 2 | 加载后 `data.soul` 含文件内容 | 内容正确 |
| 3 | 缺 SOUL.md → 抛 `FileNotFoundError` | 必选校验 |
| 4 | 缺 USER.md → 抛 `FileNotFoundError` | 必选校验 |
| 5 | 缺 MEMORY.md → 抛 `FileNotFoundError` | 必选校验 |
| 6 | 缺 IDENTITY.md → 抛 `FileNotFoundError` | 必选校验 |
| 7 | 缺 HEARTBEAT.md → 抛 `FileNotFoundError` | 必选校验 |
| 8 | 缺 whisper.md → 静默，`data.whisper == ""` | 可选文件 |
| 9 | 加载失败后 `data is None` | 失败态明确 |
| 10 | `reload()` 后内容随文件变化更新 | 热重载 |
| 11 | `soul_dir` 显式传入路径正常工作 | 可配置路径 |
| 12 | 文件含 UTF-8 中文正确读取 | 编码 |

### `agent/tests/test_system_prompt_builder.py`（9 用例）

| # | 用例 | 覆盖点 | 优先级 |
|---|------|--------|--------|
| 1 | 无角色、无 whisper → prompt 含所有灵魂段 | 主路径 | P1 |
| 2 | 无角色、有 whisper → prompt 含私密档案段 | whisper 注入 | P1 |
| 3 | 有 persona_md → prompt 含 persona 段 | 简化路径 | P1 |
| 4 | tool_overlay 空字符串 → 不追加空段 | 空值处理 | P1 |
| 5 | soul.data is None → 抛 RuntimeError | 防御检查 | P1 |
| 6 | prompt 段顺序：SOUL 在 USER 前 | 顺序铁律 | P1 |
| 7 | 段之间含分隔线 `─` | 分隔符 | P1 |
| 8 | logger.debug 调用含 chars 长度信息 | 可观测性 | P1 |
| 9 | HEARTBEAT.md 内容非空 → prompt 含自检铁规段 | HEARTBEAT 注入 | P2 |

---

## 七、.gitignore 追加

```
soul/whisper.md
```

---

## 八、产物清单

| 产物 | 类型 | 说明 |
|------|------|------|
| `soul/SOUL.md` | 新建 | 真话/边界/气质/主动防御 |
| `soul/USER.md` | 新建 | 用户画像/称呼/学习体系 |
| `soul/MEMORY.md` | 新建 | 精选记忆/事件日志/主题索引 |
| `soul/IDENTITY.md` | 新建 | 名字/身份/风格/签名 |
| `soul/HEARTBEAT.md` | 新建 | 自检铁规（注入 system prompt） |
| `soul/whisper.md` | 新建 | 私密档案（git-ignored，可空） |
| `agent/soul/loader.py` | 新建 | SoulLoader 类 |
| `agent/core/system_prompt_builder.py` | 新建 | SystemPromptBuilder 类 |
| `agent/tests/test_soul_loader.py` | 新建 | 12 个单元测试 |
| `agent/tests/test_system_prompt_builder.py` | 新建 | 9 个单元测试 |
| `agent/prompts/prompt_manager.py` | 修改 | 追加 `get_overlay()` |
| `agent/__init__.py` | 修改 | 预导入 SoulLoader/SystemPromptBuilder |
| `agent/core/conversation_flow.py` | 修改 | 替换 `system_prompt` 属性 |
| `agent/core/agent.py` | 修改 | 追加 soul 初始化 |
| `.gitignore` | 修改 | 追加 `soul/whisper.md` |
