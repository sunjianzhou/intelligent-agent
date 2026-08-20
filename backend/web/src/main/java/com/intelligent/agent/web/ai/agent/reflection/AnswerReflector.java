package com.intelligent.agent.web.ai.agent.reflection;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;

import java.util.List;

/**
 * 答案自检器（G6 reflection 后验）：对草稿答案做一次对照检查并可能修订。
 * <p>
 * 实现必须优雅降级——任何失败/超时都原样返回草稿，保证功能不退化。
 *
 * @param context     请求上下文（提供 userId/model/message）
 * @param draftAnswer 待检查的草稿答案
 * @param toolResults 工具执行结果摘要（已截断，可为空）
 * @param planSteps   执行计划步骤（可为空）
 * @return 修订后的答案；无法修订时原样返回草稿
 */
public interface AnswerReflector {

    String reflect(AgentRequestContext context, String draftAnswer,
                   List<String> toolResults, List<String> planSteps);
}
