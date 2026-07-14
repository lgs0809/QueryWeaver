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
package cn.lgs.queryweaver.semantic.retrieval;

import cn.lgs.queryweaver.common.json.CanonicalJson;
import cn.lgs.queryweaver.semantic.application.SemanticCatalogFingerprint;
import cn.lgs.queryweaver.semantic.domain.BusinessQueryScenario;
import cn.lgs.queryweaver.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.queryweaver.semantic.domain.ProjectEvidence;
import cn.lgs.queryweaver.semantic.domain.ProjectEvidence.EvidenceType;
import cn.lgs.queryweaver.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticAssetStatus;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.queryweaver.semantic.retrieval.SemanticRetrievalDocument.DocumentType;
import cn.lgs.queryweaver.semantic.retrieval.SemanticRetrievalEnrichmentService.EnrichmentInput;
import cn.lgs.queryweaver.semantic.retrieval.SemanticRetrievalEnrichmentService.EnrichmentResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Materializes version-bound retrieval artifacts from the authoritative Semantic Catalog.
 */
@Service
public class SemanticRetrievalDocumentBuildService {

	private static final int MAX_EVIDENCE = 12;

	private static final int MAX_SCENARIOS = 8;

	private static final int MAX_MODEL_LEXICAL_COLUMNS = 200;

	private static final int ENRICHMENT_CONCURRENCY = 3;

	private final SemanticCatalogRepository catalogRepository;

	private final ProjectEvidenceRepository evidenceRepository;

	private final BusinessQueryScenarioRepository scenarioRepository;

	private final SemanticRetrievalDocumentRepository documentRepository;

	private final SemanticRetrievalEnrichmentService enrichmentService;

	private final SemanticRetrievalIndexService indexService;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	public SemanticRetrievalDocumentBuildService(SemanticCatalogRepository catalogRepository,
			ProjectEvidenceRepository evidenceRepository, BusinessQueryScenarioRepository scenarioRepository,
			SemanticRetrievalDocumentRepository documentRepository,
			SemanticRetrievalEnrichmentService enrichmentService, SemanticRetrievalIndexService indexService) {
		this.catalogRepository = catalogRepository;
		this.evidenceRepository = evidenceRepository;
		this.scenarioRepository = scenarioRepository;
		this.documentRepository = documentRepository;
		this.enrichmentService = enrichmentService;
		this.indexService = indexService;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public BuildResult build(Long projectId, Long projectVersionId, String catalogHash) {
		SemanticCatalogSnapshot snapshot = catalogRepository.loadCatalog(projectId, projectVersionId);
		assertCatalogHash(snapshot, catalogHash);
		List<ProjectEvidence> evidence = safe(evidenceRepository.findActiveEvidenceByVersion(projectVersionId));
		List<BusinessQueryScenario> scenarios = safe(scenarioRepository.findActiveByVersion(projectVersionId));
		Map<String, SemanticCatalogSnapshot.Model> models = enabled(snapshot.getModels()).stream()
			.collect(Collectors.toMap(SemanticCatalogSnapshot.Model::getModelCode, Function.identity(),
					(left, right) -> right, LinkedHashMap::new));
		Map<String, SemanticCatalogSnapshot.Column> columns = enabled(snapshot.getColumns()).stream()
			.collect(Collectors.toMap(column -> key(column.getModelCode(), column.getColumnName()), Function.identity(),
					(left, right) -> right, LinkedHashMap::new));
		Map<String, Set<String>> allowedAssetKeysByModel = governedAssetKeysByModel(snapshot);
		List<SourceDocument> sources = new ArrayList<>();
		for (SemanticCatalogSnapshot.Model model : models.values()) {
			sources.add(modelSource(snapshot, model, columns, evidence, scenarios,
					allowedAssetKeysByModel.getOrDefault(model.getModelCode(), Set.of())));
		}
		for (SemanticCatalogSnapshot.Metric metric : enabled(snapshot.getMetrics())) {
			SemanticCatalogSnapshot.Model model = models.get(metric.getModelCode());
			if (model != null) {
				sources.add(metricSource(metric, model, evidence, scenarios,
						allowedAssetKeysByModel.getOrDefault(model.getModelCode(), Set.of())));
			}
		}
		for (SemanticCatalogSnapshot.Dimension dimension : enabled(snapshot.getDimensions())) {
			SemanticCatalogSnapshot.Model model = models.get(dimension.getModelCode());
			SemanticCatalogSnapshot.Column column = columns
				.get(key(dimension.getModelCode(), dimension.getColumnName()));
			if (model != null && sendable(column)) {
				sources.add(dimensionSource(dimension, model, evidence, scenarios,
						allowedAssetKeysByModel.getOrDefault(model.getModelCode(), Set.of())));
			}
		}
		for (SemanticCatalogSnapshot.EnumValue value : enabled(snapshot.getEnumValues())) {
			SemanticCatalogSnapshot.Model model = models.get(value.getModelCode());
			SemanticCatalogSnapshot.Column column = columns.get(key(value.getModelCode(), value.getColumnName()));
			if (model != null && sendable(column)) {
				sources.add(enumSource(value, model, evidence, scenarios,
						allowedAssetKeysByModel.getOrDefault(model.getModelCode(), Set.of())));
			}
		}

		List<EnrichmentResult> enrichments = enrichSources(projectId, projectVersionId, catalogHash, sources);
		List<String> ids = new ArrayList<>();
		int enriched = 0;
		int fallback = 0;
		for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
			SourceDocument source = sources.get(sourceIndex);
			EnrichmentResult enrichment = enrichments.get(sourceIndex);
			if ("ENRICHED".equals(enrichment.generationStatus())) {
				enriched++;
			}
			else {
				fallback++;
			}
			SemanticRetrievalDocument document = retrievalDocument(projectId, projectVersionId, catalogHash, source,
					enrichment);
			documentRepository.upsert(document);
			ids.add(document.id());
		}
		documentRepository.deleteStale(projectVersionId, catalogHash, ids);
		assertCatalogHash(catalogRepository.loadCatalog(projectId, projectVersionId), catalogHash);
		List<SemanticRetrievalDocument> documents = documentRepository.findCatalog(projectId, projectVersionId,
				catalogHash);
		SemanticRetrievalIndexService.IndexingResult indexing = indexService.indexDocuments(documents);
		indexService.assertReady(projectId, projectVersionId, catalogHash);
		return new BuildResult(documents.size(), enriched, fallback, indexing.indexedDocuments(),
				indexing.vectorAvailable());
	}

	private List<EnrichmentResult> enrichSources(Long projectId, Long projectVersionId, String catalogHash,
			List<SourceDocument> sources) {
		ExecutorService executor = Executors.newFixedThreadPool(ENRICHMENT_CONCURRENCY);
		try {
			List<EnrichmentResult> results = new ArrayList<>(sources.size());
			for (int offset = 0; offset < sources.size(); offset += ENRICHMENT_CONCURRENCY) {
				List<SourceDocument> batch = sources.subList(offset,
						Math.min(sources.size(), offset + ENRICHMENT_CONCURRENCY));
				List<CompletableFuture<EnrichmentResult>> futures = batch.stream()
					.map(source -> CompletableFuture.supplyAsync(() -> enrichSource(projectVersionId, source), executor))
					.toList();
				for (int batchIndex = 0; batchIndex < batch.size(); batchIndex++) {
					EnrichmentResult enrichment = awaitEnrichment(futures.get(batchIndex));
					SourceDocument source = batch.get(batchIndex);
					documentRepository
						.upsert(retrievalDocument(projectId, projectVersionId, catalogHash, source, enrichment));
					results.add(enrichment);
				}
			}
			return List.copyOf(results);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private EnrichmentResult awaitEnrichment(CompletableFuture<EnrichmentResult> future) {
		try {
			return future.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Semantic retrieval enrichment build was interrupted", ex);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Semantic retrieval enrichment build failed", cause);
		}
	}

	private SemanticRetrievalDocument retrievalDocument(Long projectId, Long projectVersionId, String catalogHash,
			SourceDocument source, EnrichmentResult enrichment) {
		String id = canonicalJson.hash(Map.of("projectVersionId", projectVersionId, "documentType",
				source.documentType().name(), "assetKey", source.assetKey()));
		String contentHash = canonicalJson.hash(enrichment.semanticText());
		return new SemanticRetrievalDocument(id, projectId, projectVersionId, catalogHash, source.documentType(),
				source.assetType(), source.assetKey(), source.datasourceId(), source.modelCode(), source.physicalTable(),
				source.lexicalText(), enrichment.semanticText(), source.sourceFingerprint(), contentHash,
				enrichment.generatorModel(), enrichment.generatorVersion(), enrichment.generationStatus());
	}

	private EnrichmentResult enrichSource(Long projectVersionId, SourceDocument source) {
		SemanticRetrievalDocument existing = documentRepository
			.findExisting(projectVersionId, source.documentType(), source.assetKey())
			.orElse(null);
		if (existing != null && Objects.equals(existing.sourceFingerprint(), source.sourceFingerprint())
				&& reusableGenerationStatus(existing.generationStatus())) {
			return new EnrichmentResult(existing.semanticText(), existing.generatorModel(), existing.generatorVersion(),
					existing.generationStatus());
		}
		return enrichmentService.enrich(source.enrichmentInput());
	}

	private boolean reusableGenerationStatus(String generationStatus) {
		return "ENRICHED".equals(generationStatus) || "FALLBACK_VALIDATION".equals(generationStatus);
	}

	public void assertReady(Long projectId, Long projectVersionId, String catalogHash) {
		documentRepository.assertCatalogVersion(projectId, projectVersionId, catalogHash);
		indexService.assertConfiguredIndexCompatible();
		indexService.assertReady(projectId, projectVersionId, catalogHash);
	}

	public int reindexEmbeddings() {
		return indexService.reindexAll();
	}

	public SemanticRetrievalIndexService.IndexingResult reindexEmbeddings(Long projectId, Long projectVersionId) {
		List<SemanticRetrievalDocument> documents = documentRepository.findVersion(projectId, projectVersionId);
		if (documents.isEmpty()) {
			return new SemanticRetrievalIndexService.IndexingResult(0, false);
		}
		long catalogHashCount = documents.stream().map(SemanticRetrievalDocument::catalogHash).distinct().count();
		if (catalogHashCount != 1) {
			throw new IllegalStateException("Semantic retrieval documents contain multiple catalog hashes for project version "
					+ projectVersionId);
		}
		return indexService.indexDocuments(documents);
	}

	private SourceDocument modelSource(SemanticCatalogSnapshot snapshot, SemanticCatalogSnapshot.Model model,
			Map<String, SemanticCatalogSnapshot.Column> columns, List<ProjectEvidence> evidence,
			List<BusinessQueryScenario> scenarios, Set<String> allowedAssetKeys) {
		String assetKey = "model:" + model.getModelCode();
		LinkedHashMap<String, String> facts = facts("modelCode", model.getModelCode(), "businessName",
				model.getBusinessName(), "modelType", model.getModelType(), "description", model.getDescription());
		List<String> lexical = new ArrayList<>(facts.values());
		lexical.add(model.getPhysicalTable());
		snapshot.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(column -> Objects.equals(model.getModelCode(), column.getModelCode()))
			.filter(this::sendable)
			.sorted(Comparator.comparing(SemanticCatalogSnapshot.Column::getColumnName,
					Comparator.nullsLast(String::compareTo)))
			.limit(MAX_MODEL_LEXICAL_COLUMNS)
			.forEach(column -> {
				lexical.add(column.getColumnName());
				lexical.add(column.getBusinessName());
				lexical.add(column.getSynonyms());
			});
		return source(DocumentType.MODEL, "MODEL", assetKey, model, facts, lexical,
				relevantEvidence(evidence, EvidenceType.MODEL, model.getModelCode(), model.getModelCode()),
				relevantScenarios(scenarios, model.getModelCode(), model.getBusinessName(), model.getDescription()),
				allowedAssetKeys);
	}

	private SourceDocument metricSource(SemanticCatalogSnapshot.Metric metric, SemanticCatalogSnapshot.Model model,
			List<ProjectEvidence> evidence, List<BusinessQueryScenario> scenarios, Set<String> allowedAssetKeys) {
		String assetKey = "metric:" + metric.getMetricCode();
		LinkedHashMap<String, String> facts = facts("metricCode", metric.getMetricCode(), "businessName",
				metric.getBusinessName(), "aggregation", metric.getAggregation(), "unit", metric.getUnit(),
				"description", metric.getDescription(), "modelBusinessName", model.getBusinessName());
		return source(DocumentType.METRIC, "METRIC", assetKey, model, facts, new ArrayList<>(facts.values()),
				relevantEvidence(evidence, EvidenceType.METRIC, metric.getMetricCode(), model.getModelCode()),
				relevantScenarios(scenarios, metric.getMetricCode(), metric.getBusinessName(), metric.getDescription(),
						model.getBusinessName()),
				allowedAssetKeys);
	}

	private SourceDocument dimensionSource(SemanticCatalogSnapshot.Dimension dimension,
			SemanticCatalogSnapshot.Model model, List<ProjectEvidence> evidence, List<BusinessQueryScenario> scenarios,
			Set<String> allowedAssetKeys) {
		String assetKey = "dimension:" + dimension.getDimensionCode();
		LinkedHashMap<String, String> facts = facts("dimensionCode", dimension.getDimensionCode(), "businessName",
				dimension.getBusinessName(), "columnName", dimension.getColumnName(), "dimensionType",
				dimension.getDimensionType(), "hierarchy", dimension.getHierarchy(), "description",
				dimension.getDescription(), "modelBusinessName", model.getBusinessName());
		return source(DocumentType.DIMENSION, "DIMENSION", assetKey, model, facts, new ArrayList<>(facts.values()),
				relevantEvidence(evidence, EvidenceType.DIMENSION, dimension.getDimensionCode(), model.getModelCode()),
				relevantScenarios(scenarios, dimension.getDimensionCode(), dimension.getBusinessName(),
						dimension.getDescription(), model.getBusinessName()),
				allowedAssetKeys);
	}

	private SourceDocument enumSource(SemanticCatalogSnapshot.EnumValue value, SemanticCatalogSnapshot.Model model,
			List<ProjectEvidence> evidence, List<BusinessQueryScenario> scenarios, Set<String> allowedAssetKeys) {
		String subjectKey = key(key(value.getModelCode(), value.getColumnName()), value.getValueCode());
		String assetKey = "enum_value:" + subjectKey;
		LinkedHashMap<String, String> facts = facts("valueCode", value.getValueCode(), "businessName",
				value.getBusinessName(), "aliases", value.getAliases(), "columnName", value.getColumnName(),
				"description", value.getDescription(), "modelBusinessName", model.getBusinessName());
		return source(DocumentType.ENUM_VALUE, "ENUM_VALUE", assetKey, model, facts, new ArrayList<>(facts.values()),
				relevantEvidence(evidence, EvidenceType.ENUM_VALUE, subjectKey, model.getModelCode()),
				relevantScenarios(scenarios, value.getValueCode(), value.getBusinessName(), value.getAliases(),
						value.getDescription(), model.getBusinessName()),
				allowedAssetKeys);
	}

	private SourceDocument source(DocumentType documentType, String assetType, String assetKey,
			SemanticCatalogSnapshot.Model model, Map<String, String> facts, List<String> lexicalParts,
			List<String> evidence, List<String> scenarios, Set<String> allowedAssetKeys) {
		String lexicalText = join(lexicalParts);
		String fallbackSemanticText = facts.entrySet()
			.stream()
			.map(entry -> entry.getKey() + ": " + entry.getValue())
			.collect(Collectors.joining("\n"));
		EnrichmentInput input = new EnrichmentInput(documentType.name(), assetType, assetKey, model.getModelCode(),
				model.getPhysicalTable(), facts, allowedAssetKeys, evidence, scenarios, fallbackSemanticText);
		String sourceFingerprint = canonicalJson.hash(Map.of("input", input, "lexicalText", lexicalText,
				"generatorVersion", enrichmentService.generatorVersion()));
		return new SourceDocument(documentType, assetType, assetKey, model.getDatasourceId(), model.getModelCode(),
				model.getPhysicalTable(), lexicalText, sourceFingerprint, input);
	}

	private List<String> relevantEvidence(List<ProjectEvidence> evidence, EvidenceType type, String subjectKey,
			String modelCode) {
		return evidence.stream()
			.filter(Objects::nonNull)
			.filter(item -> item.getEvidenceType() == type || Objects.equals(item.getSubjectKey(), modelCode))
			.filter(item -> Objects.equals(item.getSubjectKey(), subjectKey)
					|| Objects.equals(item.getSubjectKey(), modelCode)
					|| (item.getSubjectKey() != null && item.getSubjectKey().startsWith(modelCode + ":")))
			.limit(MAX_EVIDENCE)
			.map(item -> abbreviate(item.getPayloadJson(), 1200))
			.filter(StringUtils::hasText)
			.toList();
	}

	private List<String> relevantScenarios(List<BusinessQueryScenario> scenarios, String... terms) {
		List<String> needles = java.util.Arrays.stream(terms)
			.filter(StringUtils::hasText)
			.map(this::normalize)
			.filter(value -> value.length() >= 2)
			.toList();
		return scenarios.stream()
			.filter(Objects::nonNull)
			.filter(scenario -> scenario.getStatus() == BusinessQueryScenario.Status.ACTIVE)
			.filter(scenario -> {
				String haystack = normalize(join(java.util.Arrays.asList(scenario.getBusinessName(),
						scenario.getDescription(), scenario.getRequirementJson())));
				return needles.stream().anyMatch(haystack::contains);
			})
			.limit(MAX_SCENARIOS)
			.map(scenario -> join(java.util.Arrays.asList(scenario.getBusinessName(), scenario.getDescription(),
					scenario.getRequirementJson())))
			.map(value -> abbreviate(value, 1600))
			.toList();
	}

	private Map<String, Set<String>> governedAssetKeysByModel(SemanticCatalogSnapshot snapshot) {
		Map<String, LinkedHashSet<String>> keysByModel = new LinkedHashMap<>();
		enabled(snapshot.getModels())
			.forEach(model -> keysByModel.computeIfAbsent(model.getModelCode(), ignored -> new LinkedHashSet<>())
				.add("model:" + model.getModelCode()));
		enabled(snapshot.getMetrics())
			.forEach(metric -> addAllowed(keysByModel, metric.getModelCode(), "metric:" + metric.getMetricCode()));
		enabled(snapshot.getDimensions()).forEach(dimension -> addAllowed(keysByModel, dimension.getModelCode(),
				"dimension:" + dimension.getDimensionCode()));
		Set<String> sendableColumns = enabled(snapshot.getColumns()).stream()
			.filter(this::sendable)
			.map(column -> key(column.getModelCode(), column.getColumnName()))
			.collect(Collectors.toSet());
		enabled(snapshot.getEnumValues()).stream()
			.filter(value -> sendableColumns.contains(key(value.getModelCode(), value.getColumnName())))
			.forEach(value -> addAllowed(keysByModel, value.getModelCode(),
					"enum_value:" + key(key(value.getModelCode(), value.getColumnName()), value.getValueCode())));
		enabled(snapshot.getRelationships()).forEach(relationship -> {
			String assetKey = "relationship:" + relationship.getRelationshipCode();
			addAllowed(keysByModel, relationship.getSourceModelCode(), assetKey);
			addAllowed(keysByModel, relationship.getTargetModelCode(), assetKey);
		});
		enabled(snapshot.getRules()).stream()
			.filter(rule -> StringUtils.hasText(rule.getModelCode()))
			.forEach(rule -> addAllowed(keysByModel, rule.getModelCode(), "rule:" + rule.getRuleCode()));
		Map<String, Set<String>> result = new LinkedHashMap<>();
		keysByModel.forEach((modelCode, keys) -> result.put(modelCode, Set.copyOf(keys)));
		return Map.copyOf(result);
	}

	private void addAllowed(Map<String, LinkedHashSet<String>> keysByModel, String modelCode, String assetKey) {
		if (StringUtils.hasText(modelCode) && StringUtils.hasText(assetKey)) {
			keysByModel.computeIfAbsent(modelCode, ignored -> new LinkedHashSet<>()).add(assetKey);
		}
	}

	private LinkedHashMap<String, String> facts(String... values) {
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		for (int index = 0; index + 1 < values.length; index += 2) {
			if (StringUtils.hasText(values[index + 1])) {
				result.put(values[index], values[index + 1].trim());
			}
		}
		return result;
	}

	private boolean sendable(SemanticCatalogSnapshot.Column column) {
		return column == null || !Boolean.FALSE.equals(column.getAllowSendToLlm());
	}

	private <T> List<T> enabled(List<T> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(Objects::nonNull).filter(value -> {
			if (value instanceof SemanticCatalogSnapshot.Model model)
				return model.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.Column column)
				return column.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.Metric metric)
				return metric.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.Dimension dimension)
				return dimension.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.EnumValue enumValue)
				return enumValue.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.Relationship relationship)
				return relationship.getStatus() == SemanticAssetStatus.ENABLED;
			if (value instanceof SemanticCatalogSnapshot.Rule rule)
				return rule.getStatus() == SemanticAssetStatus.ENABLED;
			return false;
		}).toList();
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
	}

	private void assertCatalogHash(SemanticCatalogSnapshot snapshot, String catalogHash) {
		String actual = SemanticCatalogFingerprint.fingerprint(snapshot);
		if (!Objects.equals(actual, catalogHash)) {
			throw new IllegalStateException("Semantic Catalog changed while retrieval artifacts were being built");
		}
	}

	private String join(List<String> values) {
		return values == null ? ""
				: values.stream()
					.filter(StringUtils::hasText)
					.map(String::trim)
					.distinct()
					.collect(Collectors.joining(" "));
	}

	private String key(String left, String right) {
		return StringUtils.hasText(left) && StringUtils.hasText(right) ? left.trim() + ":" + right.trim() : "";
	}

	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
	}

	private String abbreviate(String value, int max) {
		if (value == null)
			return "";
		return value.length() <= max ? value : value.substring(0, max);
	}

	public record BuildResult(int documents, int enrichedDocuments, int fallbackDocuments, int indexedEmbeddings,
			boolean vectorAvailable) {
	}

	private record SourceDocument(DocumentType documentType, String assetType, String assetKey, Integer datasourceId,
			String modelCode, String physicalTable, String lexicalText, String sourceFingerprint,
			EnrichmentInput enrichmentInput) {
	}

}
