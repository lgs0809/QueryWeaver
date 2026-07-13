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
package cn.lgs.queryweaver.workflow.node;

import cn.lgs.queryweaver.dto.schema.SchemaDTO;
import cn.lgs.queryweaver.enums.TextType;
import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import cn.lgs.queryweaver.dto.planner.Plan;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.lgs.queryweaver.prompt.PromptConstant;
import cn.lgs.queryweaver.prompt.PromptHelper;
import cn.lgs.queryweaver.service.llm.LlmService;
import cn.lgs.queryweaver.util.ChatResponseUtil;
import cn.lgs.queryweaver.util.FluxUtil;
import cn.lgs.queryweaver.util.JsonUtil;
import cn.lgs.queryweaver.util.StateUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Map;

import static cn.lgs.queryweaver.constant.Constant.*;

/**
 * @author zhangshenghang
 */
@Slf4j
@Component
@AllArgsConstructor
public class PlannerNode implements NodeAction {

	private final LlmService llmService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Boolean sqlGenerationOnly = state.value(SQL_GENERATION_ONLY, false);
		String recoveredPlannerOutput = StateUtil.getStringValue(state, RECOVERED_PLANNER_OUTPUT, null);

		Flux<ChatResponse> flux = StringUtils.hasText(recoveredPlannerOutput)
				? Flux.just(ChatResponseUtil.createPureResponse(recoveredPlannerOutput))
				: sqlGenerationOnly ? handleSqlGenerationOnly() : handlePlanGenerate(state);
		if (StringUtils.hasText(recoveredPlannerOutput)) {
			log.info("Reusing exact persisted planner output for durable approval recovery");
		}

		Flux<ChatResponse> chatResponseFlux = Flux.concat(
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())), flux,
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> Map.of(PLANNER_NODE_OUTPUT, v.substring(TextType.JSON.getStartSign().length(),
						v.length() - TextType.JSON.getEndSign().length())),
				chatResponseFlux);

		return Map.of(PLANNER_NODE_OUTPUT, generator);
	}

	private Flux<ChatResponse> handlePlanGenerate(OverAllState state) {
		// 获取查询增强节点的输出
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		log.info("Using processed query for planning: {}", canonicalQuery);

		// 检查是否为修复模式
		String validationError = StateUtil.getStringValue(state, PLAN_VALIDATION_ERROR, null);
		if (validationError != null) {
			log.info("Regenerating plan with user feedback: {}", validationError);
		}
		else {
			log.info("Generating initial plan");
		}

		// 构建提示参数
		String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
		SemanticQueryPlan semanticPlan = StateUtil.getObjectValue(state, TYPED_SEMANTIC_PLAN, SemanticQueryPlan.class,
				(SemanticQueryPlan) null);
		semanticModel = semanticModel + "\n\n# Typed Semantic Plan\n" + serializeSemanticPlan(semanticPlan);
		Map<String, Object> preferredPlan = StateUtil.getObjectValue(state, PREFERRED_EXECUTION_PLAN, Map.class,
				Map.of());
		if (!preferredPlan.isEmpty()) {
			semanticModel = semanticModel + "\n\n# Preferred Execution Start Hint (NON-AUTHORITATIVE)\n"
					+ preferredPlanHint(preferredPlan)
					+ "\nThis is only a starting hint. Revalidate applicability, Typed IR, Catalog, SQL Guard, "
					+ "cost policy and human review. Ignore it whenever any condition differs.";
		}
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String schemaStr = PromptHelper.buildMixMacSqlDbPrompt(schemaDTO, true);

		// 构建用户提示
		String userPrompt = buildUserPrompt(canonicalQuery, validationError, state);
		String evidence = StateUtil.getStringValue(state, EVIDENCE);

		// 构建模板参数
		BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
		Map<String, Object> params = Map.of("user_question", userPrompt, "schema", schemaStr, "evidence", evidence,
				"semantic_model", semanticModel, "plan_validation_error", formatValidationError(validationError),
				"format", beanOutputConverter.getFormat());
		// 生成计划
		String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
		log.debug("Planner prompt: as follows \n{}\n", plannerPrompt);

		// 调用LLM生成计划
		return llmService.callUser(plannerPrompt);
	}

	private Flux<ChatResponse> handleSqlGenerationOnly() {
		return Flux.just(ChatResponseUtil.createPureResponse(Plan.nl2SqlPlan()));
	}

	private String buildUserPrompt(String input, String validationError, OverAllState state) {
		if (validationError == null) {
			return input;
		}

		String previousPlan = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT, "");
		return String.format(
				"IMPORTANT: User rejected previous plan with feedback: \"%s\"\n\n" + "Original question: %s\n\n"
						+ "Previous rejected plan:\n%s\n\n"
						+ "CRITICAL: Generate new plan incorporating user feedback (\"%s\")",
				validationError, input, previousPlan, validationError);
	}

	private String formatValidationError(String validationError) {
		return validationError != null ? String
			.format("**USER FEEDBACK (CRITICAL)**: %s\n\n**Must incorporate this feedback.**", validationError) : "";
	}

	private String serializeSemanticPlan(SemanticQueryPlan semanticPlan) {
		if (semanticPlan == null) {
			return "{}";
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(semanticPlan);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize typed semantic plan", ex);
		}
	}

	private String preferredPlanHint(Map<String, Object> preferredPlan) {
		Map<String, Object> safe = new java.util.LinkedHashMap<>();
		safe.put("preferredPlanId", preferredPlan.get("id"));
		safe.put("candidateId", preferredPlan.get("candidate_id"));
		safe.put("proposal", preferredPlan.get("plan_json"));
		safe.put("startHintOnly", true);
		safe.put("guardStillRequired", true);
		safe.put("reviewStillRequired", true);
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(safe);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize preferred execution start hint", ex);
		}
	}

}
