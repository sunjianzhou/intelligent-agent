# Eval 趋势报告（自动生成）

生成时间：2026-09-17 21:03

> 数据来源：`D:\workspace\intelligent_agent\backend\web\target\eval-results` 下的 `eval-*.jsonl`；由 `EvalTrendReporter` 汇总，跑 `mvn -Peval test` 会自动刷新本文件。

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

## 最近一次全量运行逐用例（2026-08-25 21:40，36 例）

| 用例 | 分类 | 得分 | 上一轮 | 变化 |
|---|---|---|---|---|
| calc-001 | tool | 5 | 5 | 0 |
| calc-002 | tool | 10 | - | - |
| calc-003 | tool | 10 | - | - |
| calc-004 | tool | 10 | - | - |
| calc-005 | tool | 8 | - | - |
| unit-001 | tool | 10 | 10 | 0 |
| unit-002 | tool | 5 | - | - |
| unit-003 | tool | 8 | - | - |
| unit-004 | tool | 5 | - | - |
| time-001 | tool | 5 | 10 | -5 |
| time-002 | tool | 10 | - | - |
| tool-combo-001 | tool | 5 | - | - |
| tool-combo-002 | tool | 8 | - | - |
| tool-combo-003 | tool | 8 | - | - |
| qa-001 | knowledge | 10 | 10 | 0 |
| qa-002 | knowledge | 10 | - | - |
| qa-003 | knowledge | 10 | - | - |
| qa-004 | knowledge | 10 | - | - |
| qa-005 | knowledge | 10 | - | - |
| qa-006 | knowledge | 10 | - | - |
| qa-007 | knowledge | 10 | - | - |
| qa-008 | knowledge | 10 | - | - |
| memory-001 | memory | 8 | 9 | -1 |
| memory-002 | memory | 0 | - | - |
| memory-003 | memory | 5 | - | - |
| memory-004 | memory | 7 | - | - |
| memory-search-001 | memory | 0 | - | - |
| correction-001 | memory | 0 | - | - |
| compression-001 | memory | 5 | - | - |
| injection-memory-001 | tool | 0 | - | - |
| injection-user-001 | knowledge | 10 | - | - |
| web-001 | tool | 2 | 5 | -3 |
| persona-001 | persona | 8 | 7 | +1 |
| persona-002 | persona | 8 | - | - |
| group-silence-001 | scene | 10 | 10 | 0 |
| group-mentioned-001 | scene | 10 | - | - |
