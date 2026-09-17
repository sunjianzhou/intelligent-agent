# QA 浏览器审计脚本

从 2026-08-30 移动端布局巡检沉淀下来的可复用脚本，用于在没有测试框架介入的情况下，
对真实渲染页面做几何审计（读 DOM 尺寸与 computed style，不是源码推测）。

> 仓库约定：QA 截图体积大、不入库（见 `.gitignore`「QA 截图」一节），只有脚本入库。

## 脚本

| 文件 | 作用 |
|------|------|
| `layout-check.js` | 横向溢出（元素超出视口左右边界）、fixed 元素位置、零尺寸可见元素 —— 用于"页面是否横向溢出 / 内容被裁切"的快速判定 |
| `layout-audit.js` | 更细的审计：文本被 `overflow:hidden` / `ellipsis` 裁切、fixed 元素互相重叠、fixed 元素跑出视口、最高可见元素 |

两个脚本都是「浏览器内 eval 的自执行函数」，返回 JSON 字符串，无外部依赖，
可对任意页面使用（前端 `/qa` 之外的场景也适用）。

## 用法（gstack browse）

```powershell
$B = "<gstack 安装目录>/browse/dist/browse.exe"
& $B open http://localhost:5173/tasks
& $B eval ./tests/qa/layout-check.js
& $B console --errors
& $B screenshot .qa/tasks-mobile.png
```

移动端复测先把视口切到 375x812，桌面基线用 1280x720。

## 历史

- 2026-08-30：用这两个脚本 + 逐页点击完成桌面 15 页 / 移动端 375px 巡检，
  发现并修复 3 处移动端布局问题（见 `TODOS.md` 2026-08-30 节）。
- 当时的驱动脚本（`click-admin.ps1`，逐页点击后台导航 + 截图）与全部截图留在本地
  `.qa/`，未入库。
