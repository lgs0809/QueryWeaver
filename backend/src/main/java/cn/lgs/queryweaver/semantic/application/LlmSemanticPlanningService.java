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
package cn.lgs.queryweaver.semantic.application;

import cn.lgs.queryweaver.learning.QueryCaseHints;
import cn.lgs.queryweaver.model.ModelCallPurpose;
import cn.lgs.queryweaver.model.PlannerReasoningProperties;
import cn.lgs.queryweaver.model.QueryWeaverModelGateway.ModelCallResult;
import cn.lgs.queryweaver.service.llm.LlmInvocationOptions;
import cn.lgs.queryweaver.learning.QueryCaseHints.EnumBindingHint;
import cn.lgs.queryweaver.learning.QueryCaseHints.FilterBindingHint;
import cn.lgs.queryweaver.learning.QueryCaseHints.TimeBindingHint;
import cn.lgs.queryweaver.semantic.domain.SemanticAssetStatus;
import cn.lgs.queryweaver.semantic.domain.SemanticCandidateSet;
import cn.lgs.queryweaver.semantic.domain.SemanticCandidateSet.RetrievalEvidence;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.queryweaver.semantic.domain.SemanticColumnRole;
import cn.lgs.queryweaver.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.queryweaver.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Governed LLM semantic planning boundary.
 *
 * <p>The model is allowed to choose only already-published Semantic Catalog assets. It never
 * supplies SQL, metric formulae, join predicates, datasource identifiers or arbitrary columns.
 * QueryWeaver validates every selected code against the candidate Catalog slice and then lets the
 * deterministic semantic resolver expand those codes into the authoritative Typed Plan.
 */
@Service
public class LlmSemanticPlanningService {

	private static final int MAX_CANDIDATE_MODELS = 24;

	private static final int RELATIONSHIP_NEIGHBORHOOD_DEPTH = 2;

	private static final Set<String> SUPPORTED_LITERAL_FILTER_OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT",
			"LTE", "IN");

	private static final Set<String> SUPPORTED_TIME_GROUP_GRANULARITIES = Set.of("DAY", "MONTH", "YEAR");

	private static final Set<String> QUERY_SELECTABLE_RULE_TYPES = Set.of("BUSINESS_RULE", "BUSINESS_FILTER");

	private static final Set<String> MANDATORY_GOVERNANCE_RULE_TYPES = Set.of("MANDATORY_FILTER", "ROW_FILTER",
			"SECURITY_FILTER", "DATA_SCOPE", "REQUIRED_PREDICATE");

	private static final String SYSTEM_PROMPT = """
			You are QueryWeaver's governed semantic planner.
			Your job is semantic binding, not SQL generation. Select the smallest sufficient set of already-published
			Semantic Catalog assets that exactly represent the user's request.

			STRICT RULES:
			1. Use only codes and enum values present in the supplied candidates. Never invent a model, metric,
			   dimension, rule, relationship, grain, enum value, column, formula, join condition, datasource or SQL.
			2. metricCodes contains only measures actually requested by the user. Prefer the most specific business
			   meaning; do not choose a nearby metric merely because its name is similar.
			3. dimensionCodes contains only fields the user wants returned/grouped as dimensions or entity labels.
			   A field mentioned only to filter rows MUST NOT also become a dimension.
			4. enumBindings represents categorical filters whose value is one of the supplied published enum values.
			5. filters represents non-enum literal predicates. Use only supplied filterableColumns and copy literal values
			   from the current question; never invent a literal. Do not duplicate an enumBinding as a filter.
			6. ruleCodes contains only supplied querySelectableRules implied by the user's business wording. planningPolicies
			   and mandatoryGovernanceRules are constraints, never selectable ruleCodes. When an exact supplied business rule
			   represents the user's business wording, select that ruleCode instead of reconstructing the same business concept
			   from a lower-level enumBinding. Do not emit a duplicate enumBinding for a predicate expanded by that selected rule.
			7. If the question contains a date/range/relative time expression, timeBinding MUST choose the published
			   business-event time column appropriate to the requested metric/event. Supplied timeColumns are governed,
			   filter-approved time-range bindings and are intentionally listed separately from filterableColumns; never
			   require a time column to also appear in filterableColumns. If the user explicitly asks for daily, monthly or
			   yearly grouping, set groupGranularity to DAY, MONTH or YEAR respectively; otherwise it is null.
			8. relationshipCodes contains only published relationships necessary to connect the selected semantic assets.
			9. grainCodes contains only published grains explicitly required for the requested result semantics.
			10. Do not add context that the user did not ask for. Minimal sufficient plan wins.
			11. historicalHints are non-authoritative prior experience and may be reused only when the current question
			    and current Catalog candidates independently support them. requiredHints are explicit user/runtime
			    constraints and MUST be preserved exactly when present.
			12. Return status=NEEDS_CLARIFICATION only when two or more supplied governed candidates represent materially
			    different plausible meanings and the current question/requiredHints cannot distinguish them. Clarification
			    options must reference only supplied candidate asset codes. Do not use clarification to hide a retrieval miss.
			13. Return status=UNRESOLVABLE when the supplied governed candidates cannot represent the requested meaning.

			For a resolved request return exactly one JSON object and no Markdown:
			{
			  "status": "RESOLVED",
			  "metricCodes": ["published_metric_code"],
			  "dimensionCodes": ["published_dimension_code"],
			  "ruleCodes": ["published_rule_code"],
			  "relationshipCodes": ["published_relationship_code"],
			  "grainCodes": ["published_grain_code"],
			  "enumBindings": [
			    {"modelCode":"published_model_code","columnName":"published_column","valueCode":"published_value"}
			  ],
			  "filters": [
			    {"modelCode":"published_model_code","columnName":"published_filterable_column","operator":"EQ","value":"literal copied from question"}
			  ],
			  "timeBinding": {"modelCode":"published_model_code","columnName":"published_time_column","groupGranularity":null},
			  "confidence": 0.0
			}

			For an ambiguity return:
			{"status":"NEEDS_CLARIFICATION","clarification":{"issueType":"SEMANTIC_AMBIGUITY","question":"one concise business question","options":[{"code":"option-code","label":"business label","assetType":"METRIC","assetKey":"published_asset_code"}],"reason":"why the supplied candidates remain ambiguous"}}

			If the supplied governed candidates cannot represent the request return:
			{"status":"UNRESOLVABLE","reason":"which required governed meaning is absent"}
			""";

	private final SemanticCatalogRepository catalogRepository;

	private final SemanticDocumentExtractionClient extractionClient;

	private final PlannerReasoningProperties reasoningProperties;

	@Autowired
	public LlmSemanticPlanningService(SemanticCatalogRepository catalogRepository,
			SemanticDocumentExtractionClient extractionClient, PlannerReasoningProperties reasoningProperties) {
		this.catalogRepository = catalogRepository;
		this.extractionClient = extractionClient;
		this.reasoningProperties = reasoningProperties;
	}

	LlmSemanticPlanningService(SemanticCatalogRepository catalogRepository,
			SemanticDocumentExtractionClient extractionClient) {
		this.catalogRepository = catalogRepository;
		this.extractionClient = extractionClient;
		this.reasoningProperties = new PlannerReasoningProperties();
		this.reasoningProperties.setEnabled(false);
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits) {
		return plan(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, QueryCaseHints.empty(),
				QueryCaseHints.empty());
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints) {
		return plan(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, historicalHints,
				QueryCaseHints.empty());
	}

	public QueryCaseHints plan(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		SemanticPlanningOutcome outcome = planOutcome(projectId, projectVersionId, query, selectedPhysicalTables,
				retrievalHits, historicalHints, requiredHints);
		if (outcome instanceof SemanticPlanningOutcome.Resolved resolved) {
			return resolved.binding();
		}
		if (outcome instanceof SemanticPlanningOutcome.ClarificationRequired clarification) {
			throw new SemanticPlanningClarificationRequiredException(clarification);
		}
		SemanticPlanningOutcome.Rejected rejected = (SemanticPlanningOutcome.Rejected) outcome;
		throw new SemanticPlanningRejectedException(rejected.errorCode(), rejected.reason());
	}

	public SemanticPlanningOutcome planOutcome(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		return planDecision(projectId, projectVersionId, query, selectedPhysicalTables, retrievalHits, historicalHints,
				requiredHints, PlannerProfile.CONFIGURED).outcome();
	}

	public PlanningDecision planDecision(Long projectId, Long projectVersionId, String query,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints, PlannerProfile profile) {
		if (!StringUtils.hasText(query)) {
			return new PlanningDecision(
					new SemanticPlanningOutcome.Rejected("BLANK_QUERY", "Semantic planning query cannot be blank"), List.of());
		}
		SemanticCandidateSet candidates = candidates(projectId, projectVersionId, selectedPhysicalTables, retrievalHits);
		if (candidates.models().isEmpty()) {
			return new PlanningDecision(new SemanticPlanningOutcome.Rejected("NO_CANDIDATE_MODEL",
					"Semantic planner has no governed candidate models"), List.of());
		}
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, profile);
	}

	public SemanticPlanningOutcome planOutcome(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, PlannerProfile.CONFIGURED)
			.outcome();
	}

	public SemanticPlanningOutcome planOutcome(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile) {
		return planDecision(query, candidates, retrievalHits, historicalHints, requiredHints, profile).outcome();
	}

	public PlanningDecision planDecision(String query, SemanticCandidateSet candidates,
			Collection<RetrievalHit> retrievalHits, QueryCaseHints historicalHints, QueryCaseHints requiredHints,
			PlannerProfile profile) {
		String userPrompt = userPrompt(query, candidates, retrievalHits, historicalHints, requiredHints);
		List<ModelCallResult> calls = new ArrayList<>();
		ModelCallResult initialCall = completePlanner(profile, SYSTEM_PROMPT, userPrompt);
		calls.add(initialCall);
		String response = initialCall.response();
		SemanticPlanningOutcome explicit = explicitNonResolvedOutcome(response);
		if (explicit != null) {
			return new PlanningDecision(explicit, calls);
		}
		try {
			return new PlanningDecision(
					new SemanticPlanningOutcome.Resolved(parseAndValidate(query, response, candidates, requiredHints)), calls);
		}
		catch (IllegalArgumentException firstFailure) {
			String repairPrompt = userPrompt + "\n\nYour previous response was rejected by QueryWeaver: "
					+ safeError(firstFailure.getMessage())
					+ "\nReturn a corrected JSON object using only the supplied candidate codes.";
			ModelCallResult repairCall = completePlanner(profile, SYSTEM_PROMPT, repairPrompt);
			calls.add(repairCall);
			String repaired = repairCall.response();
			SemanticPlanningOutcome repairedExplicit = explicitNonResolvedOutcome(repaired);
			if (repairedExplicit != null) {
				return new PlanningDecision(repairedExplicit, calls);
			}
			try {
				return new PlanningDecision(
						new SemanticPlanningOutcome.Resolved(parseAndValidate(query, repaired, candidates, requiredHints)), calls);
			}
			catch (IllegalArgumentException finalFailure) {
				return new PlanningDecision(new SemanticPlanningOutcome.Rejected("INVALID_GOVERNED_SELECTION",
						safeError(finalFailure.getMessage())), calls);
			}
		}
	}

	private ModelCallResult completePlanner(PlannerProfile profile, String systemPrompt, String userPrompt) {
		PlannerProfile effectiveProfile = profile == null ? PlannerProfile.CONFIGURED : profile;
		return switch (effectiveProfile) {
			case CONFIGURED -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt);
			case BASELINE -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt,
					LlmInvocationOptions.none());
			case REASONING -> extractionClient.complete(ModelCallPurpose.SEMANTIC_PLANNING, systemPrompt, userPrompt,
					new LlmInvocationOptions(reasoningProperties.getModelOverride(), reasoningProperties.getEffort()));
		};
	}

	public SemanticCandidateSet candidates(Long projectId, Long projectVersionId,
			Collection<String> selectedPhysicalTables, Collection<RetrievalHit> retrievalHits) {
		SemanticCatalogSnapshot snapshot = catalogRepository.loadCatalog(projectId, projectVersionId);
		Set<String> selectedTables = selectedPhysicalTables == null ? Set.of()
				: selectedPhysicalTables.stream().filter(StringUtils::hasText)
					.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> seedModels = new LinkedHashSet<>();
		for (SemanticCatalogSnapshot.Model model : safe(snapshot.getModels())) {
			if (model.getStatus() == SemanticAssetStatus.ENABLED && selectedTables.contains(model.getPhysicalTable())) {
				seedModels.add(model.getModelCode());
			}
		}
		for (RetrievalHit hit : safeHits(retrievalHits)) {
			if (StringUtils.hasText(hit.modelCode())) {
				seedModels.add(hit.modelCode());
			}
		}

		Set<String> modelCodes = relationshipNeighborhood(snapshot, seedModels);
		if (modelCodes.size() > MAX_CANDIDATE_MODELS) {
			modelCodes = seedModels.stream().limit(MAX_CANDIDATE_MODELS).collect(Collectors.toCollection(LinkedHashSet::new));
		}
		Set<String> finalModelCodes = Set.copyOf(modelCodes);
		List<SemanticCatalogSnapshot.Model> models = safe(snapshot.getModels()).stream()
			.filter(model -> model.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(model -> finalModelCodes.contains(model.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Model::getModelCode))
			.toList();
		List<SemanticCatalogSnapshot.Metric> metrics = safe(snapshot.getMetrics()).stream()
			.filter(metric -> metric.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(metric -> finalModelCodes.contains(metric.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Metric::getMetricCode))
			.toList();
		List<SemanticCatalogSnapshot.Dimension> dimensions = safe(snapshot.getDimensions()).stream()
			.filter(dimension -> dimension.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(dimension -> finalModelCodes.contains(dimension.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Dimension::getDimensionCode))
			.toList();
		List<SemanticCatalogSnapshot.EnumValue> enumValues = safe(snapshot.getEnumValues()).stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> finalModelCodes.contains(value.getModelCode()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.EnumValue value) -> value.getModelCode() + "::"
					+ value.getColumnName() + "::" + value.getValueCode()))
			.toList();
		List<SemanticCatalogSnapshot.Rule> inScopeRules = safe(snapshot.getRules()).stream()
			.filter(rule -> rule.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(rule -> !StringUtils.hasText(rule.getModelCode()) || finalModelCodes.contains(rule.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Rule::getRuleCode))
			.toList();
		List<SemanticCatalogSnapshot.Rule> rules = inScopeRules.stream().filter(this::querySelectableRule).toList();
		List<SemanticCatalogSnapshot.Rule> mandatoryGovernanceRules = inScopeRules.stream()
			.filter(this::mandatoryGovernanceRule)
			.toList();
		List<SemanticCatalogSnapshot.Rule> planningPolicies = inScopeRules.stream()
			.filter(rule -> !querySelectableRule(rule) && !mandatoryGovernanceRule(rule))
			.toList();
		List<SemanticCatalogSnapshot.Relationship> relationships = safe(snapshot.getRelationships()).stream()
			.filter(relationship -> relationship.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(relationship -> finalModelCodes.contains(relationship.getSourceModelCode())
					&& finalModelCodes.contains(relationship.getTargetModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Relationship::getRelationshipCode))
			.toList();
		List<SemanticCatalogSnapshot.Grain> grains = safe(snapshot.getGrains()).stream()
			.filter(grain -> grain.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(grain -> finalModelCodes.contains(grain.getModelCode()))
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Grain::getGrainCode))
			.toList();
		List<SemanticCatalogSnapshot.Column> timeColumns = safe(snapshot.getColumns()).stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> finalModelCodes.contains(column.getModelCode()))
			.filter(column -> column.getRole() == SemanticColumnRole.TIME)
			.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
			.filter(column -> Boolean.TRUE.equals(column.getAllowSendToLlm()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.Column column) -> column.getModelCode() + "::"
					+ column.getColumnName()))
			.toList();
		List<SemanticCatalogSnapshot.Column> filterableColumns = safe(snapshot.getColumns()).stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> finalModelCodes.contains(column.getModelCode()))
			.filter(column -> column.getRole() != SemanticColumnRole.TIME)
			.filter(column -> Boolean.TRUE.equals(column.getAllowFilter()))
			.filter(column -> Boolean.TRUE.equals(column.getAllowSendToLlm()))
			.sorted(Comparator.comparing((SemanticCatalogSnapshot.Column column) -> column.getModelCode() + "::"
					+ column.getColumnName()))
			.toList();
		List<RetrievalEvidence> retrievalEvidence = retrievalHits == null ? List.of()
				: retrievalHits.stream()
					.map(hit -> new RetrievalEvidence(hit.documentType() == null ? null : hit.documentType().name(),
							hit.assetType(), hit.assetKey(), hit.modelCode(), hit.physicalTable(), hit.score(),
							hit.channelRanks(), hit.channelScores()))
					.toList();
		return new SemanticCandidateSet(projectId, projectVersionId, SemanticCatalogFingerprint.fingerprint(snapshot),
				selectedTables, models, metrics, dimensions, enumValues, rules, mandatoryGovernanceRules, planningPolicies,
				relationships, grains, timeColumns, filterableColumns, retrievalEvidence);
	}

	private Set<String> relationshipNeighborhood(SemanticCatalogSnapshot snapshot, Set<String> seeds) {
		Set<String> models = new LinkedHashSet<>(seeds);
		for (int depth = 0; depth < RELATIONSHIP_NEIGHBORHOOD_DEPTH; depth++) {
			Set<String> additions = new LinkedHashSet<>();
			for (SemanticCatalogSnapshot.Relationship relationship : safe(snapshot.getRelationships())) {
				if (relationship.getStatus() != SemanticAssetStatus.ENABLED) {
					continue;
				}
				if (models.contains(relationship.getSourceModelCode())) {
					additions.add(relationship.getTargetModelCode());
				}
				if (models.contains(relationship.getTargetModelCode())) {
					additions.add(relationship.getSourceModelCode());
				}
			}
			if (!models.addAll(additions)) {
				break;
			}
		}
		return models;
	}

	private String userPrompt(String query, SemanticCandidateSet candidates, Collection<RetrievalHit> retrievalHits,
			QueryCaseHints historicalHints, QueryCaseHints requiredHints) {
		Map<String, RetrievalHit> hitByAsset = safeHits(retrievalHits).stream()
			.collect(Collectors.toMap(RetrievalHit::assetKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("question", query);
		payload.put("models", candidates.models().stream().map(model -> mapOf("modelCode", model.getModelCode(),
				"businessName", model.getBusinessName(), "physicalTable", model.getPhysicalTable(), "description",
				model.getDescription())).toList());
		payload.put("metrics", candidates.metrics().stream().map(metric -> withRetrieval(mapOf("metricCode",
				metric.getMetricCode(), "modelCode", metric.getModelCode(), "businessName", metric.getBusinessName(),
				"aggregation", metric.getAggregation(), "timeColumn", metric.getTimeColumn(), "description",
				metric.getDescription(), "authoritativeExpression", metric.getExpression()),
				hitByAsset.get("metric:" + metric.getMetricCode()))).toList());
		payload.put("dimensions", candidates.dimensions().stream().map(dimension -> withRetrieval(mapOf("dimensionCode",
				dimension.getDimensionCode(), "modelCode", dimension.getModelCode(), "businessName",
				dimension.getBusinessName(), "columnName", dimension.getColumnName(), "dimensionType",
				dimension.getDimensionType(), "description", dimension.getDescription()),
				hitByAsset.get("dimension:" + dimension.getDimensionCode()))).toList());
		payload.put("enumValues", candidates.enumValues().stream().map(value -> withRetrieval(mapOf("modelCode",
				value.getModelCode(), "columnName", value.getColumnName(), "valueCode", value.getValueCode(),
				"businessName", value.getBusinessName(), "aliases", value.getAliases(), "description",
				value.getDescription()), hitByAsset.get(enumAssetKey(value)))).toList());
		payload.put("querySelectableRules", candidates.querySelectableRules().stream().map(this::rulePrompt).toList());
		payload.put("mandatoryGovernanceRules", candidates.mandatoryGovernanceRules().stream().map(this::rulePrompt).toList());
		payload.put("planningPolicies", candidates.planningPolicies().stream().map(this::rulePrompt).toList());
		payload.put("relationships", candidates.relationships().stream().map(relationship -> mapOf("relationshipCode",
				relationship.getRelationshipCode(), "sourceModelCode", relationship.getSourceModelCode(), "targetModelCode",
				relationship.getTargetModelCode(), "cardinality", Objects.toString(relationship.getCardinality(), null),
				"description", relationship.getDescription(), "authoritativeJoinCondition",
				relationship.getJoinCondition())).toList());
		payload.put("grains", candidates.grains().stream().map(grain -> mapOf("grainCode", grain.getGrainCode(),
				"modelCode", grain.getModelCode(), "keyColumns", grain.getKeyColumns(), "timeColumn",
				grain.getTimeColumn(), "description", grain.getDescription())).toList());
		payload.put("timeColumns", candidates.timeColumns().stream().map(column -> mapOf("modelCode", column.getModelCode(),
				"columnName", column.getColumnName(), "businessName", column.getBusinessName(), "synonyms",
				column.getSynonyms(), "role", "TIME", "timeRangeFilterable", true, "description", column.getDescription()))
			.toList());
		payload.put("filterableColumns", candidates.filterableColumns().stream()
			.map(column -> mapOf("modelCode", column.getModelCode(), "columnName", column.getColumnName(), "businessName",
					column.getBusinessName(), "synonyms", column.getSynonyms(), "role", Objects.toString(column.getRole(), null),
					"dataType", column.getDataType(), "description", column.getDescription()))
			.toList());
		QueryCaseHints historical = historicalHints == null ? QueryCaseHints.empty() : historicalHints;
		if (!historical.emptyHints()) {
			payload.put("historicalHints", hintPayload(historical));
		}
		QueryCaseHints required = requiredHints == null ? QueryCaseHints.empty() : requiredHints;
		if (!required.emptyHints()) {
			payload.put("requiredHints", hintPayload(required));
		}
		try {
			return JsonUtil.getObjectMapper().writeValueAsString(payload);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize governed semantic planning candidates", ex);
		}
	}

	private Map<String, Object> rulePrompt(SemanticCatalogSnapshot.Rule rule) {
		return mapOf("ruleCode", rule.getRuleCode(), "modelCode", rule.getModelCode(), "ruleType", rule.getRuleType(),
				"businessName", rule.getBusinessName(), "description", rule.getDescription(), "authoritativeExpression",
				rule.getExpression());
	}

	private boolean querySelectableRule(SemanticCatalogSnapshot.Rule rule) {
		return rule != null && QUERY_SELECTABLE_RULE_TYPES.contains(normalizeRuleType(rule.getRuleType()));
	}

	private boolean mandatoryGovernanceRule(SemanticCatalogSnapshot.Rule rule) {
		return rule != null && MANDATORY_GOVERNANCE_RULE_TYPES.contains(normalizeRuleType(rule.getRuleType()));
	}

	private String normalizeRuleType(String ruleType) {
		return Objects.toString(ruleType, "").trim().toUpperCase(Locale.ROOT);
	}

	private Map<String, Object> hintPayload(QueryCaseHints hints) {
		return mapOf("modelCodes", hints.modelCodes(), "metricCodes", hints.metricCodes(), "dimensionCodes",
				hints.dimensionCodes(), "grainCodes", hints.grainCodes(), "relationshipCodes", hints.relationshipCodes(),
				"ruleCodes", hints.ruleCodes(), "enumBindings", hints.enumBindings(), "filters", hints.filterBindings(),
				"timeBinding", hints.timeBinding(), "confidence", hints.confidence());
	}

	private Map<String, Object> withRetrieval(Map<String, Object> values, RetrievalHit hit) {
		Map<String, Object> result = new LinkedHashMap<>(values);
		if (hit != null) {
			result.put("retrievalScore", hit.score());
			result.put("retrievalRanks", hit.channelRanks());
		}
		return result;
	}

	private QueryCaseHints parseAndValidate(String query, String response, SemanticCandidateSet candidates,
			QueryCaseHints priorHints) {
		JsonNode root = parseObject(response);
		Set<String> metricCodes = stringSet(root.path("metricCodes"));
		Set<String> dimensionCodes = stringSet(root.path("dimensionCodes"));
		Set<String> ruleCodes = stringSet(root.path("ruleCodes"));
		Set<String> relationshipCodes = stringSet(root.path("relationshipCodes"));
		Set<String> grainCodes = stringSet(root.path("grainCodes"));

		Map<String, SemanticCatalogSnapshot.Metric> metrics = candidates.metrics().stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Metric::getMetricCode, Function.identity()));
		Map<String, SemanticCatalogSnapshot.Dimension> dimensions = candidates.dimensions().stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Dimension::getDimensionCode, Function.identity()));
		Set<String> allowedRules = candidates.querySelectableRules().stream().map(SemanticCatalogSnapshot.Rule::getRuleCode)
			.collect(Collectors.toSet());
		Set<String> allowedRelationships = candidates.relationships().stream()
			.map(SemanticCatalogSnapshot.Relationship::getRelationshipCode).collect(Collectors.toSet());
		Set<String> allowedGrains = candidates.grains().stream().map(SemanticCatalogSnapshot.Grain::getGrainCode)
			.collect(Collectors.toSet());
		assertSubset("metricCodes", metricCodes, metrics.keySet());
		assertSubset("dimensionCodes", dimensionCodes, dimensions.keySet());
		assertSubset("ruleCodes", ruleCodes, allowedRules);
		assertSubset("relationshipCodes", relationshipCodes, allowedRelationships);
		assertSubset("grainCodes", grainCodes, allowedGrains);

		double confidence = confidence(root.path("confidence"));
		List<EnumBindingHint> enumBindings = enumBindings(query, root.path("enumBindings"), candidates, confidence);
		List<FilterBindingHint> filterBindings = filterBindings(query, root.path("filters"), candidates, confidence);
		TimeBindingHint timeBinding = timeBinding(query, root.path("timeBinding"), candidates, confidence);

		Set<String> modelCodes = new LinkedHashSet<>();
		metricCodes.stream().map(metrics::get).filter(Objects::nonNull).map(SemanticCatalogSnapshot.Metric::getModelCode)
			.forEach(modelCodes::add);
		dimensionCodes.stream().map(dimensions::get).filter(Objects::nonNull)
			.map(SemanticCatalogSnapshot.Dimension::getModelCode).forEach(modelCodes::add);
		enumBindings.stream().map(EnumBindingHint::modelCode).forEach(modelCodes::add);
		filterBindings.stream().map(FilterBindingHint::modelCode).forEach(modelCodes::add);
		if (timeBinding != null) {
			modelCodes.add(timeBinding.modelCode());
		}
		for (SemanticCatalogSnapshot.Rule rule : candidates.querySelectableRules()) {
			if (ruleCodes.contains(rule.getRuleCode()) && StringUtils.hasText(rule.getModelCode())) {
				modelCodes.add(rule.getModelCode());
			}
		}
		for (SemanticCatalogSnapshot.Relationship relationship : candidates.relationships()) {
			if (relationshipCodes.contains(relationship.getRelationshipCode())) {
				modelCodes.add(relationship.getSourceModelCode());
				modelCodes.add(relationship.getTargetModelCode());
			}
		}

		if (metricCodes.isEmpty() && dimensionCodes.isEmpty()) {
			throw new IllegalArgumentException("LLM semantic plan selected no governed projection metric or dimension");
		}
		assertRelationshipSelection(modelCodes, relationshipCodes, candidates.relationships());
		QueryCaseHints result = new QueryCaseHints(Set.copyOf(modelCodes), metricCodes, dimensionCodes, grainCodes,
				relationshipCodes, ruleCodes, enumBindings, filterBindings, List.of(), timeBinding, true,
				"LLM_SEMANTIC_PLANNER", List.of(), confidence, Map.of("semanticPlanner", confidence));
		assertRequiredPriorBindings(result, priorHints);
		return result;
	}

	private void assertRequiredPriorBindings(QueryCaseHints result, QueryCaseHints priorHints) {
		if (priorHints == null || !priorHints.strictAssetBinding()) {
			return;
		}
		assertSubset("required modelCodes", priorHints.modelCodes(), result.modelCodes());
		assertSubset("required metricCodes", priorHints.metricCodes(), result.metricCodes());
		assertSubset("required dimensionCodes", priorHints.dimensionCodes(), result.dimensionCodes());
		assertSubset("required grainCodes", priorHints.grainCodes(), result.grainCodes());
		assertSubset("required relationshipCodes", priorHints.relationshipCodes(), result.relationshipCodes());
		assertSubset("required ruleCodes", priorHints.ruleCodes(), result.ruleCodes());
		for (EnumBindingHint required : priorHints.enumBindings()) {
			boolean present = result.enumBindings().stream().anyMatch(binding -> Objects.equals(required.modelCode(),
					binding.modelCode()) && Objects.equals(required.columnName(), binding.columnName())
					&& Objects.equals(required.valueCode(), binding.valueCode()));
			if (!present) {
				throw new IllegalArgumentException("LLM semantic plan dropped required enum binding: " + required.modelCode()
						+ "." + required.columnName() + "=" + required.valueCode());
			}
		}
		for (FilterBindingHint required : priorHints.filterBindings()) {
			boolean present = result.filterBindings().stream().anyMatch(binding -> Objects.equals(required.modelCode(),
					binding.modelCode()) && Objects.equals(required.columnName(), binding.columnName())
					&& Objects.equals(required.operator(), binding.operator()) && Objects.equals(required.value(), binding.value()));
			if (!present) {
				throw new IllegalArgumentException("LLM semantic plan dropped required literal filter binding");
			}
		}
		if (priorHints.timeBinding() != null) {
			TimeBindingHint selected = result.timeBinding();
			if (selected == null || !Objects.equals(priorHints.timeBinding().modelCode(), selected.modelCode())
					|| !Objects.equals(priorHints.timeBinding().columnName(), selected.columnName())) {
				throw new IllegalArgumentException("LLM semantic plan dropped required time binding");
			}
		}
	}

	private void assertRelationshipSelection(Set<String> modelCodes, Set<String> relationshipCodes,
			List<SemanticCatalogSnapshot.Relationship> candidates) {
		if (modelCodes.size() <= 1) {
			return;
		}
		if (relationshipCodes.isEmpty()) {
			throw new IllegalArgumentException("relationshipCodes must connect all selected semantic models");
		}
		Map<String, Set<String>> adjacency = new LinkedHashMap<>();
		for (String modelCode : modelCodes) {
			adjacency.put(modelCode, new LinkedHashSet<>());
		}
		for (SemanticCatalogSnapshot.Relationship relationship : candidates) {
			if (!relationshipCodes.contains(relationship.getRelationshipCode())) {
				continue;
			}
			adjacency.computeIfAbsent(relationship.getSourceModelCode(), ignored -> new LinkedHashSet<>())
				.add(relationship.getTargetModelCode());
			adjacency.computeIfAbsent(relationship.getTargetModelCode(), ignored -> new LinkedHashSet<>())
				.add(relationship.getSourceModelCode());
		}
		Set<String> visited = new LinkedHashSet<>();
		List<String> pending = new ArrayList<>();
		pending.add(modelCodes.iterator().next());
		for (int index = 0; index < pending.size(); index++) {
			String current = pending.get(index);
			if (visited.add(current)) {
				adjacency.getOrDefault(current, Set.of()).stream().filter(modelCodes::contains).forEach(pending::add);
			}
		}
		if (!visited.containsAll(modelCodes)) {
			throw new IllegalArgumentException("relationshipCodes do not connect all selected semantic models");
		}
	}

	private List<FilterBindingHint> filterBindings(String query, JsonNode node, SemanticCandidateSet candidates,
			double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return List.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("filters must be an array");
		}
		Map<String, SemanticCatalogSnapshot.Column> allowedColumns = candidates.filterableColumns()
			.stream()
			.collect(Collectors.toMap(column -> columnKey(column.getModelCode(), column.getColumnName()),
					Function.identity()));
		List<FilterBindingHint> bindings = new ArrayList<>();
		for (JsonNode item : node) {
			String modelCode = text(item, "modelCode");
			String columnName = text(item, "columnName");
			String operator = text(item, "operator").toUpperCase(Locale.ROOT);
			if (!SUPPORTED_LITERAL_FILTER_OPERATORS.contains(operator)) {
				throw new IllegalArgumentException("filters contains unsupported operator: " + operator);
			}
			SemanticCatalogSnapshot.Column column = allowedColumns.get(columnKey(modelCode, columnName));
			if (column == null) {
				throw new IllegalArgumentException("filters contains non-candidate filterable column: " + modelCode + "."
						+ columnName);
			}
			JsonNode valueNode = item.get("value");
			if (valueNode == null || valueNode.isNull()) {
				throw new IllegalArgumentException("filters.value is required");
			}
			Object value = literalValue(valueNode);
			validateFilterValueShape(operator, value);
			if (!literalComesFromQuestion(query, value)) {
				throw new IllegalArgumentException("filters contains a literal that is not present in the current question");
			}
			if (duplicatesPublishedEnum(candidates, modelCode, columnName, value)) {
				throw new IllegalArgumentException("filters duplicates a published enum value; use enumBindings instead");
			}
			bindings.add(new FilterBindingHint(literalRawText(value), modelCode, columnName, operator, value,
					"LLM_SEMANTIC_PLANNER", confidence));
		}
		return List.copyOf(bindings);
	}

	private Object literalValue(JsonNode valueNode) {
		if (valueNode.isTextual()) {
			return valueNode.asText();
		}
		if (valueNode.isIntegralNumber()) {
			return valueNode.longValue();
		}
		if (valueNode.isFloatingPointNumber()) {
			return valueNode.doubleValue();
		}
		if (valueNode.isBoolean()) {
			return valueNode.booleanValue();
		}
		if (valueNode.isArray()) {
			List<Object> values = new ArrayList<>();
			for (JsonNode child : valueNode) {
				if (child.isContainerNode() || child.isNull()) {
					throw new IllegalArgumentException("filters array values must contain only scalar literals");
				}
				values.add(literalValue(child));
			}
			return List.copyOf(values);
		}
		throw new IllegalArgumentException("filters.value must be a scalar literal or scalar array");
	}

	private void validateFilterValueShape(String operator, Object value) {
		if ("IN".equals(operator)) {
			if (!(value instanceof List<?> values) || values.isEmpty()) {
				throw new IllegalArgumentException("IN filter requires a non-empty literal array");
			}
			return;
		}
		if (value instanceof Collection<?>) {
			throw new IllegalArgumentException(operator + " filter requires one scalar literal");
		}
	}

	private boolean literalComesFromQuestion(String query, Object value) {
		String normalizedQuery = normalizeNaturalText(query);
		if (value instanceof Collection<?> values) {
			return values.stream().allMatch(item -> literalComesFromQuestion(query, item));
		}
		String literal = normalizeNaturalText(Objects.toString(value, ""));
		return StringUtils.hasText(literal) && normalizedQuery.contains(literal);
	}

	private boolean duplicatesPublishedEnum(SemanticCandidateSet candidates, String modelCode, String columnName,
			Object value) {
		if (value instanceof Collection<?> values) {
			return values.stream().anyMatch(item -> duplicatesPublishedEnum(candidates, modelCode, columnName, item));
		}
		String normalized = normalizeNaturalText(Objects.toString(value, ""));
		return candidates.enumValues()
			.stream()
			.filter(candidate -> Objects.equals(candidate.getModelCode(), modelCode)
					&& Objects.equals(candidate.getColumnName(), columnName))
			.anyMatch(candidate -> normalized.equals(normalizeNaturalText(candidate.getValueCode()))
					|| normalized.equals(normalizeNaturalText(candidate.getBusinessName())));
	}

	private String literalRawText(Object value) {
		if (value instanceof Collection<?> values) {
			return values.stream().map(item -> Objects.toString(item, "")).collect(Collectors.joining(","));
		}
		return Objects.toString(value, "");
	}

	private String columnKey(String modelCode, String columnName) {
		return Objects.toString(modelCode, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(columnName, "").toLowerCase(Locale.ROOT);
	}

	private String normalizeNaturalText(String value) {
		return java.text.Normalizer.normalize(Objects.toString(value, ""), java.text.Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", "");
	}

	private List<EnumBindingHint> enumBindings(String query, JsonNode node, SemanticCandidateSet candidates,
			double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return List.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("enumBindings must be an array");
		}
		Set<String> allowed = candidates.enumValues().stream().map(this::enumKey).collect(Collectors.toSet());
		List<EnumBindingHint> bindings = new ArrayList<>();
		for (JsonNode item : node) {
			String modelCode = text(item, "modelCode");
			String columnName = text(item, "columnName");
			String valueCode = text(item, "valueCode");
			String key = enumKey(modelCode, columnName, valueCode);
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException("enumBindings contains non-candidate value: " + key);
			}
			bindings.add(new EnumBindingHint(query, modelCode, columnName, valueCode, "LLM_SEMANTIC_PLANNER",
					confidence));
		}
		return List.copyOf(bindings);
	}

	private TimeBindingHint timeBinding(String query, JsonNode node, SemanticCandidateSet candidates, double confidence) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isObject()) {
			throw new IllegalArgumentException("timeBinding must be an object or null");
		}
		String modelCode = text(node, "modelCode");
		String columnName = text(node, "columnName");
		boolean allowed = candidates.timeColumns().stream().anyMatch(column -> Objects.equals(modelCode, column.getModelCode())
				&& Objects.equals(columnName, column.getColumnName()));
		if (!allowed) {
			throw new IllegalArgumentException("timeBinding contains non-candidate time column: " + modelCode + "."
					+ columnName);
		}
		String groupGranularity = nullableText(node, "groupGranularity");
		if (StringUtils.hasText(groupGranularity)) {
			groupGranularity = groupGranularity.toUpperCase(Locale.ROOT);
			if (!SUPPORTED_TIME_GROUP_GRANULARITIES.contains(groupGranularity)) {
				throw new IllegalArgumentException("timeBinding contains unsupported groupGranularity: " + groupGranularity);
			}
		}
		return new TimeBindingHint(query, modelCode, columnName, "LLM_SEMANTIC_PLANNER", confidence,
				groupGranularity);
	}

	private SemanticPlanningOutcome explicitNonResolvedOutcome(String response) {
		JsonNode root = parseObject(response);
		String status = root.path("status").asText("RESOLVED").trim().toUpperCase(Locale.ROOT);
		if ("RESOLVED".equals(status)) {
			return null;
		}
		if ("NEEDS_CLARIFICATION".equals(status)) {
			JsonNode clarification = root.path("clarification");
			String issueType = nullableText(clarification, "issueType");
			String question = nullableText(clarification, "question");
			String reason = nullableText(clarification, "reason");
			List<SemanticPlanningOutcome.Option> options = new ArrayList<>();
			JsonNode optionNodes = clarification.path("options");
			if (optionNodes.isArray()) {
				for (JsonNode option : optionNodes) {
					options.add(new SemanticPlanningOutcome.Option(nullableText(option, "code"),
							nullableText(option, "label"), nullableText(option, "assetType"),
							nullableText(option, "assetKey")));
				}
			}
			if (!StringUtils.hasText(question)) {
				question = "The governed semantic candidates do not uniquely determine the requested business meaning.";
			}
			return new SemanticPlanningOutcome.ClarificationRequired(
					StringUtils.hasText(issueType) ? issueType : "SEMANTIC_AMBIGUITY", question, options, reason);
		}
		String reason = nullableText(root, "reason");
		return new SemanticPlanningOutcome.Rejected("MODEL_UNRESOLVABLE",
				StringUtils.hasText(reason) ? reason : "Semantic planner marked the question as unresolvable");
	}

	private JsonNode parseObject(String response) {
		try {
			String trimmed = Objects.toString(response, "").trim();
			if (trimmed.startsWith("```")) {
				int firstLine = trimmed.indexOf('\n');
				int closing = trimmed.lastIndexOf("```");
				if (firstLine >= 0 && closing > firstLine) {
					trimmed = trimmed.substring(firstLine + 1, closing).trim();
				}
			}
			int start = trimmed.indexOf('{');
			int end = trimmed.lastIndexOf('}');
			if (start < 0 || end < start) {
				throw new IllegalArgumentException("Semantic planner returned no JSON object");
			}
			JsonNode root = JsonUtil.getObjectMapper().readTree(trimmed.substring(start, end + 1));
			if (!root.isObject()) {
				throw new IllegalArgumentException("Semantic planner JSON root must be an object");
			}
			return root;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid semantic planner JSON", ex);
		}
	}

	private Set<String> stringSet(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return Set.of();
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("Semantic planner asset selection must be an array");
		}
		Set<String> values = new LinkedHashSet<>();
		for (JsonNode item : node) {
			if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
				throw new IllegalArgumentException("Semantic planner asset code must be a non-blank string");
			}
			values.add(item.asText().trim());
		}
		return Set.copyOf(values);
	}

	private void assertSubset(String field, Set<String> selected, Set<String> allowed) {
		Set<String> invalid = selected.stream().filter(value -> !allowed.contains(value))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!invalid.isEmpty()) {
			throw new IllegalArgumentException(field + " contains non-candidate assets: " + String.join(",", invalid));
		}
	}

	private double confidence(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return 0.90d;
		}
		return Math.max(0.0d, Math.min(1.0d, node.asDouble()));
	}

	private String text(JsonNode node, String field) {
		String value = node == null ? null : node.path(field).asText(null);
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Semantic planner field is required: " + field);
		}
		return value.trim();
	}

	private String nullableText(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull() || value.isMissingNode()) {
			return null;
		}
		String text = value.asText(null);
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private String enumAssetKey(SemanticCatalogSnapshot.EnumValue value) {
		return "enum_value:" + value.getModelCode() + ":" + value.getColumnName() + ":" + value.getValueCode();
	}

	private String enumKey(SemanticCatalogSnapshot.EnumValue value) {
		return enumKey(value.getModelCode(), value.getColumnName(), value.getValueCode());
	}

	private String enumKey(String modelCode, String columnName, String valueCode) {
		return Objects.toString(modelCode, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(columnName, "").toLowerCase(Locale.ROOT) + "::"
				+ Objects.toString(valueCode, "").toLowerCase(Locale.ROOT);
	}

	private Map<String, Object> mapOf(Object... values) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index + 1 < values.length; index += 2) {
			Object value = values[index + 1];
			if (value != null && (!(value instanceof String string) || StringUtils.hasText(string))) {
				result.put(Objects.toString(values[index]), value);
			}
		}
		return result;
	}

	private String safeError(String message) {
		String safe = Objects.toString(message, "invalid governed semantic selection").replaceAll("[\\r\\n]+", " ");
		return safe.length() <= 300 ? safe : safe.substring(0, 300);
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	private List<RetrievalHit> safeHits(Collection<RetrievalHit> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	public enum PlannerProfile {
		CONFIGURED,
		BASELINE,
		REASONING
	}

	public record PlanningDecision(SemanticPlanningOutcome outcome, List<ModelCallResult> modelCalls) {
		public PlanningDecision {
			modelCalls = List.copyOf(modelCalls == null ? List.of() : modelCalls);
		}
	}

}
