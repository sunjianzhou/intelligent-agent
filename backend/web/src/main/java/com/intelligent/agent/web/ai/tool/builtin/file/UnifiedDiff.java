package com.intelligent.agent.web.ai.tool.builtin.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简行级 unified diff（LCS 基础），供 {@code FileTool.preview} 生成
 * 人类/模型可读的变更预览。超过行数上限时退化为整文件替换表示，避免内存失控。
 */
final class UnifiedDiff {

    /** 超过该行数的文件不做行级 diff（LCS 是 O(n*m)）。 */
    private static final int MAX_LINES = 1200;
    private static final int CONTEXT = 3;

    private UnifiedDiff() {
    }

    /** 生成 {@code path} 从 before → after 的 unified diff；内容相同返回“无变更”。 */
    static String of(String path, String before, String after) {
        List<String> a = split(before);
        List<String> b = split(after);
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return "diff --git a/" + path + " b/" + path + "\n"
                    + "--- a/" + path + "\n+++ b/" + path + "\n"
                    + "（文件行数超过 " + MAX_LINES + "，不做行级 diff）\n"
                    + "-（原 " + a.size() + " 行，已省略）\n"
                    + "+（新 " + b.size() + " 行，已省略）\n";
        }
        List<Op> ops = walk(a, b, lcs(a, b));
        return render(path, ops);
    }

    private static List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(List.of(text.split("\\R", -1)));
    }

    private static int[][] lcs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private static List<Op> walk(List<String> a, List<String> b, int[][] dp) {
        List<Op> ops = new ArrayList<>();
        int i = a.size();
        int j = b.size();
        while (i > 0 && j > 0) {
            if (a.get(i - 1).equals(b.get(j - 1))) {
                ops.add(new Op(' ', a.get(i - 1)));
                i--;
                j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                ops.add(new Op('-', a.get(i - 1)));
                i--;
            } else {
                ops.add(new Op('+', b.get(j - 1)));
                j--;
            }
        }
        while (i > 0) {
            ops.add(new Op('-', a.get(i - 1)));
            i--;
        }
        while (j > 0) {
            ops.add(new Op('+', b.get(j - 1)));
            j--;
        }
        Collections.reverse(ops);
        return ops;
    }

    private static String render(String path, List<Op> ops) {
        boolean hasChange = ops.stream().anyMatch(op -> op.kind != ' ');
        if (!hasChange) {
            return "（内容无变化）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("--- a/").append(path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        int i = 0;
        while (i < ops.size()) {
            while (i < ops.size() && ops.get(i).kind == ' ') {
                i++;
            }
            if (i >= ops.size()) {
                break;
            }
            int changeEnd = i;
            while (changeEnd < ops.size() && ops.get(changeEnd).kind != ' ') {
                changeEnd++;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = Math.min(ops.size(), changeEnd + CONTEXT);
            int oldCount = 0;
            int newCount = 0;
            for (int k = start; k < end; k++) {
                if (ops.get(k).kind != '+') {
                    oldCount++;
                }
                if (ops.get(k).kind != '-') {
                    newCount++;
                }
            }
            int oldStart = 1;
            int newStart = 1;
            for (int k = 0; k < start; k++) {
                if (ops.get(k).kind != '+') {
                    oldStart++;
                }
                if (ops.get(k).kind != '-') {
                    newStart++;
                }
            }
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount)
                    .append(" @@\n");
            for (int k = start; k < end; k++) {
                sb.append(ops.get(k).kind).append(ops.get(k).text).append('\n');
            }
            i = end;
        }
        return sb.toString().stripTrailing();
    }

    private record Op(char kind, String text) {
    }
}
