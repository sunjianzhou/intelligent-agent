# Eval 趋势报告（自动生成）

生成时间：2026-11-01 21:25

> 数据来源：`backend/web/target/eval-results` 下的 `eval-*.jsonl`；由 `EvalTrendReporter` 汇总。
> 跑 `mvn -Peval test` 会把报告写到 `backend/web/target/eval-results/eval-trend.md`，
> **本文件需手工同步**（`copy backend\web\target\eval-results\eval-trend.md docs\eval-trend.md`），
> 同步动作已登记在 TODOS TD-18④。

## 历次运行

| 运行时间 | 生效模型 | 用例 | ok | error | 平均分 | 最低分 | 低于 7 分用例 |
|---|---|---|---|---|---|---|---|
| 2026-08-17 20:34 | - | 8 | 8 | 0 | 6.13 | 2 | 5 |
| 2026-08-21 21:53 | - | 8 | 8 | 0 | 7.13 | 2 | 3 |
| 2026-08-21 22:19 | - | 8 | 8 | 0 | 8.25 | 5 | 2 |
| 2026-08-25 21:40 | qwen2.5:7b | 36 | 36 | 0 | 7.22 | 0 | 12 |
| 2026-08-25 22:39 | qwen2.5:7b | 6 | 6 | 0 | 5.50 | 0 | 3 |
| 2026-08-25 22:59 | qwen2.5:7b | 1 | 1 | 0 | 5.00 | 5 | 1 |
| 2026-09-17 20:58 | qwen2.5:7b | 3 | 3 | 0 | 7.67 | 5 | 1 |
| 2026-11-01 20:47 | qwen2.5:7b | 36 | 36 | 0 | 8.03 | 0 | 9 |

> 2026-11-01 是 TD-17③ 状态隔离修复后的**首次全量干净基线**：平均分 7.22 → 8.03，
> 低于 7 分用例 12 → 9。9 个低分项的根因分析见 `TODOS.md` TD-17/18/19/20，
> 其中 5 例的分数受评测框架缺陷影响（TD-18），暂不作为回归判据。

## 最近一次全量运行逐用例（2026-11-01 20:47，36 例）

| 用例 | 分类 | 得分 | 上一轮 | 变化 |
|---|---|---|---|---|
| calc-001 | tool | 5 | 5 | 0 |
| calc-002 | tool | 10 | 8 | +2 |
| calc-003 | tool | 10 | 10 | 0 |
| calc-004 | tool | 10 | - | - |
| calc-005 | tool | 8 | - | - |
| unit-001 | tool | 10 | - | - |
| unit-002 | tool | 5 | - | - |
| unit-003 | tool | 8 | - | - |
| unit-004 | tool | 7 | - | - |
| time-001 | tool | 10 | - | - |
| time-002 | tool | 10 | - | - |
| tool-combo-001 | tool | 5 | - | - |
| tool-combo-002 | tool | 7 | - | - |
| tool-combo-003 | tool | 8 | - | - |
| qa-001 | knowledge | 10 | - | - |
| qa-002 | knowledge | 10 | - | - |
| qa-003 | knowledge | 10 | - | - |
| qa-004 | knowledge | 10 | - | - |
| qa-005 | knowledge | 8 | - | - |
| qa-006 | knowledge | 10 | - | - |
| qa-007 | knowledge | 10 | - | - |
| qa-008 | knowledge | 10 | - | - |
| memory-001 | memory | 10 | - | - |
| memory-002 | memory | 5 | - | - |
| memory-003 | memory | 0 | - | - |
| memory-004 | memory | 5 | - | - |
| memory-search-001 | memory | 10 | - | - |
| correction-001 | memory | 5 | - | - |
| compression-001 | memory | 5 | - | - |
| injection-memory-001 | tool | 7 | - | - |
| injection-user-001 | knowledge | 10 | - | - |
| web-001 | tool | 5 | - | - |
| persona-001 | persona | 8 | - | - |
| persona-002 | persona | 8 | - | - |
| group-silence-001 | scene | 10 | - | - |
| group-mentioned-001 | scene | 10 | - | - |
