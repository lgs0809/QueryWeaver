/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.workflow.node;

import cn.lgs.semevosql.dto.planner.ExecutionStep;
import cn.lgs.semevosql.dto.planner.Plan;
import cn.lgs.semevosql.prompt.PromptHelper;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.service.llm.LlmService;
import cn.lgs.semevosql.enums.TextType;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.lgs.semevosql.util.ChatResponseUtil;
import cn.lgs.semevosql.util.FluxUtil;
import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.util.PlanProcessUtil;
import cn.lgs.semevosql.util.StateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 * Report generation node that creates comprehensive analysis reports based on execution
 * results.
 *
 * This node is responsible for: - Generating detailed analysis reports from SQL execution
 * results - Summarizing data insights and findings - Providing comprehensive answers to
 * user queries - Creating structured final output for users
 *
 */
@Slf4j
@Component
public class ReportGeneratorNode implements NodeAction {

	private final LlmService llmService;

	public ReportGeneratorNode(LlmService llmService) {
		this.llmService = llmService;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// Get necessary input parameters
		String userInput = StateUtil.getCanonicalQuery(state);
		Integer currentStep = StateUtil.getObjectValue(state, PLAN_CURRENT_STEP, Integer.class, 1);
		@SuppressWarnings("unchecked")
		Map<String, String> executionResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());
		@SuppressWarnings("unchecked")
		Map<String, String> executedQueries = StateUtil.getObjectValue(state, SQL_EXECUTED_QUERY_OUTPUT, Map.class,
				new HashMap<>());

		SemanticBlueprint typedPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticBlueprint.class,
				(SemanticBlueprint) null);
		Plan plan = typedPlan == null ? PlanProcessUtil.getPlan(state) : null;
		ExecutionStep executionStep = typedPlan == null ? getCurrentExecutionStep(plan, currentStep) : null;
		String summaryAndRecommendations = typedPlan == null
				? executionStep.getToolParameters().getSummaryAndRecommendations()
				: "请只根据受治理 Semantic Blueprint、实际执行 SQL 与实际执行结果回答，不补充未实际执行的过滤条件或业务规则。";

		// Generate report streaming flux
		Flux<ChatResponse> reportGenerationFlux = generateReport(userInput, plan, typedPlan, executionResults,
				executedQueries, summaryAndRecommendations);

		TextType reportTextType = TextType.MARK_DOWN;

		// Use utility class to create streaming generator with content collection
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始生成报告...", "报告生成完成！", reportContent -> {
					log.info("Generated report content: {}", reportContent);
					Map<String, Object> result = new HashMap<>();
					result.put(RESULT, reportContent);
					result.put(SQL_EXECUTE_NODE_OUTPUT, null);
					result.put(SQL_EXECUTED_QUERY_OUTPUT, null);
					result.put(PLAN_CURRENT_STEP, null);
					result.put(PLANNER_NODE_OUTPUT, null);
					result.put(PLAN_PARSED_OBJECT, null);
					result.put(PLAN_PARSED_OUTPUT_HASH, null);
					result.put(PLAN_VALIDATED_OUTPUT_HASH, null);
					return result;
				},
				Flux.concat(Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getStartSign())),
						reportGenerationFlux,
						Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getEndSign()))));

		return Map.of(RESULT, generator);
	}

	/**
	 * Gets the current execution step from the plan.
	 */
	private ExecutionStep getCurrentExecutionStep(Plan plan, Integer currentStep) {
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		if (executionPlan == null || executionPlan.isEmpty()) {
			throw new IllegalStateException("Execution plan is empty");
		}

		int stepIndex = currentStep - 1;
		if (stepIndex < 0 || stepIndex >= executionPlan.size()) {
			throw new IllegalStateException("Current step index out of range: " + stepIndex);
		}

		return executionPlan.get(stepIndex);
	}

	/**
	 * Generates the analysis report.
	 */
	private Flux<ChatResponse> generateReport(String userInput, Plan plan, SemanticBlueprint typedPlan,
			Map<String, String> executionResults, Map<String, String> executedQueries, String summaryAndRecommendations) {
		// SemEvoSQL reports must be grounded in the governed Semantic Blueprint and actual
		// execution evidence. The advanced planner narrative is not an execution fact.
		String userRequirementsAndPlan = buildUserRequirementsAndPlan(userInput, plan, typedPlan);

		// Build analysis steps and data results description
		String analysisStepsAndData = buildAnalysisStepsAndData(plan, executionResults, executedQueries, typedPlan != null);

		String reportPrompt = PromptHelper.buildReportGeneratorPrompt(userRequirementsAndPlan, analysisStepsAndData,
				summaryAndRecommendations);
		log.debug("Report Node Prompt: \n {} \n", reportPrompt);
		return llmService.callUser(reportPrompt);
	}

	/**
	 * Builds user requirements and plan description.
	 */
	String buildUserRequirementsAndPlan(String userInput, Plan plan, SemanticBlueprint typedPlan) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 用户原始需求\n");
		sb.append(userInput).append("\n\n");

		if (typedPlan != null) {
			sb.append("## 受治理的 Semantic Blueprint（事实约束）\n");
			sb.append("以下结构化计划是 SemEvoSQL 对本次查询意图的正式约束；不得使用 advanced fallback planner 的思考过程或参数描述补充其中不存在的过滤条件。\n");
			sb.append("```json\n").append(json(typedPlan)).append("\n```\n\n");
			return sb.toString();
		}

		sb.append("## 执行计划概述\n");
		sb.append("**思考过程**: ").append(plan.getThoughtProcess()).append("\n\n");

		sb.append("## 详细执行步骤\n");
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		for (int i = 0; i < executionPlan.size(); i++) {
			ExecutionStep step = executionPlan.get(i);
			sb.append("### 步骤 ").append(i + 1).append(": 步骤编号 ").append(step.getStep()).append("\n");
			sb.append("**工具**: ").append(step.getToolToUse()).append("\n");
			if (step.getToolParameters() != null) {
				sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
			}
			sb.append("\n");
		}

		return sb.toString();
	}

	/**
	 * Builds analysis steps and data results description.
	 */
	String buildAnalysisStepsAndData(Plan plan, Map<String, String> executionResults,
			Map<String, String> executedQueries) {
		return buildAnalysisStepsAndData(plan, executionResults, executedQueries, false);
	}

	String buildAnalysisStepsAndData(Plan plan, Map<String, String> executionResults,
			Map<String, String> executedQueries, boolean governedTypedPlan) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 数据执行结果\n");

		if (executionResults.isEmpty()) {
			sb.append("暂无执行结果数据\n");
		}
		else {
			List<ExecutionStep> executionPlan = plan == null || plan.getExecutionPlan() == null ? List.of()
					: plan.getExecutionPlan();
			for (Map.Entry<String, String> entry : executionResults.entrySet()) {
				String stepKey = entry.getKey();
				String stepResult = entry.getValue();

				if (stepKey.endsWith("_analysis")) {
					continue;
				}

				sb.append("### ").append(stepKey).append("\n");

				// Try to get corresponding step description
				try {
					int stepIndex = Integer.parseInt(stepKey.replace("step_", "")) - 1;
					if (stepIndex >= 0 && stepIndex < executionPlan.size()) {
						ExecutionStep step = executionPlan.get(stepIndex);
						sb.append("**步骤编号**: ").append(step.getStep()).append("\n");
						sb.append("**使用工具**: ").append(step.getToolToUse()).append("\n");
						if (!governedTypedPlan && step.getToolParameters() != null) {
							sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
						}
						String executedSql = SqlExecutionLineage.queryForStep(executedQueries, stepIndex + 1);
						if (executedSql != null) {
							sb.append("**执行SQL**: \n```sql\n").append(executedSql).append("\n```\n");
						}
					}
				}
				catch (NumberFormatException e) {
					// Ignore parsing errors
				}

				sb.append("**执行结果**: \n```json\n").append(stepResult).append("\n```\n\n");
				String analysisKey = stepKey + "_analysis";
				String analysisResult = executionResults.get(analysisKey);
				if (analysisResult != null && !analysisResult.trim().isEmpty()) {
					sb.append("**Python 分析结果**: ").append(analysisResult).append(" ");
				}
			}
		}

		return sb.toString();
	}

	private String json(Object value) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize governed report facts", ex);
		}
	}

}
