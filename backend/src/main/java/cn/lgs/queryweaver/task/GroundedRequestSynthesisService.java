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
package cn.lgs.queryweaver.task;

import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import cn.lgs.queryweaver.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Deterministic final synthesis for a multi-task request.
 *
 * <p>The service only renders facts that already exist in each executed Typed Semantic Plan and durable accepted Todo
 * result. It does not invoke a model and therefore cannot add a new metric/filter/time definition during final
 * wording.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroundedRequestSynthesisService {

	private static final int MAX_RESULT_CHARS_PER_TASK = 12000;

	private final QueryTaskRepository taskRepository;

	public String synthesize(String runId, String originalRequest) {
		List<QueryTask> tasks = taskRepository.list(runId);
		if (tasks.isEmpty() || tasks.stream().anyMatch(task -> task.status() != QueryTask.TaskStatus.DONE)) {
			throw new IllegalStateException("Grounded synthesis requires all Query Todos to be DONE");
		}
		StringBuilder output = new StringBuilder();
		if (StringUtils.hasText(originalRequest)) {
			output.append("已完成：").append(originalRequest.trim()).append("\n\n");
		}
		for (int index = 0; index < tasks.size(); index++) {
			QueryTask task = tasks.get(index);
			SemanticQueryPlan plan = taskRepository.plan(runId, task.taskId());
			output.append(index + 1).append(". ").append(task.question()).append('\n');
			String planFacts = renderPlanFacts(plan);
			if (!planFacts.isBlank()) {
				output.append("口径：").append(planFacts).append('\n');
			}
			output.append("结果：").append(bounded(renderAcceptedResult(taskRepository.resultSummaryJson(runId, task.taskId()))))
				.append('\n');
			if (index + 1 < tasks.size()) {
				output.append('\n');
			}
		}
		return output.toString().trim();
	}

	private String renderAcceptedResult(String json) {
		if (!StringUtils.hasText(json)) {
			return "结果已通过验收，但持久化结果摘要为空。";
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(json);
			String report = root.path("report").asText("").trim();
			if (!report.isBlank()) {
				return report;
			}
			String payload = root.path("resultPayload").asText("").trim();
			return payload.isBlank() ? root.toString() : payload;
		}
		catch (Exception ex) {
			return json.trim();
		}
	}

	private String renderPlanFacts(SemanticQueryPlan plan) {
		if (plan == null) {
			return "";
		}
		List<String> facts = new ArrayList<>();
		plan.getMetrics().forEach(metric -> facts.add("指标=" + preferred(metric.getBusinessName(), metric.getMetricCode())));
		plan.getDimensions()
			.forEach(dimension -> facts.add("维度=" + preferred(dimension.getBusinessName(), dimension.getDimensionCode())));
		if (plan.getTimeRange() != null && StringUtils.hasText(plan.getTimeRange().getTimeColumn())) {
			facts.add("时间字段=" + plan.getTimeRange().getTimeColumn());
			if (StringUtils.hasText(plan.getTimeRange().getRelativeExpression())) {
				facts.add("时间范围=" + plan.getTimeRange().getRelativeExpression());
			}
		}
		plan.getRules().forEach(rule -> facts.add("规则=" + preferred(rule.getBusinessName(), rule.getRuleCode())));
		return String.join("；", facts);
	}

	private String preferred(String businessName, String code) {
		return StringUtils.hasText(businessName) ? businessName.trim() : code;
	}

	private String bounded(String value) {
		String text = value == null ? "" : value.trim();
		if (text.length() <= MAX_RESULT_CHARS_PER_TASK) {
			return text;
		}
		return text.substring(0, MAX_RESULT_CHARS_PER_TASK) + "…（结果已截断，完整内容保存在持久化 Run 中）";
	}
}
