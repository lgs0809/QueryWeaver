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

import cn.lgs.queryweaver.common.EmbeddingModelSupport;
import cn.lgs.queryweaver.common.json.CanonicalJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** pgvector/HNSW lifecycle for Semantic Catalog retrieval documents. */
@Service
public class SemanticRetrievalIndexService {

	public static final String INDEX_SCOPE = "SEMANTIC_CATALOG";

	// Keep each remote/local model call bounded. Small committed batches make a long catalog
	// build resumable because staleDocuments() skips vectors persisted by earlier batches.
	private static final int EMBEDDING_BATCH_SIZE = 2;

	private static final int REINDEX_BATCH_SIZE = 4;

	private static final int VECTOR_HNSW_MAX_DIMENSION = 2000;

	private static final int HALF_VECTOR_HNSW_MAX_DIMENSION = 4000;

	private static final Logger log = LoggerFactory.getLogger(SemanticRetrievalIndexService.class);

	private final JdbcTemplate jdbc;

	private final EmbeddingModel embeddingModel;

	private final EmbeddingModelIdentityProvider embeddingModelIdentityProvider;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	public SemanticRetrievalIndexService(JdbcTemplate jdbc, Optional<EmbeddingModel> embeddingModel,
			Optional<EmbeddingModelIdentityProvider> embeddingModelIdentityProvider) {
		this.jdbc = jdbc;
		this.embeddingModel = embeddingModel.orElse(null);
		this.embeddingModelIdentityProvider = embeddingModelIdentityProvider.orElse(null);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public synchronized IndexingResult indexDocuments(List<SemanticRetrievalDocument> documents) {
		if (embeddingModel == null || documents == null || documents.isEmpty()) {
			return new IndexingResult(0, false);
		}
		ConfiguredIdentity identity = configuredIdentity();
		RegistryEntry registry = registry().orElse(null);
		if (registry != null) {
			assertCompatible(identity, registry);
			ensureHnswIndex(registry.dimension());
		}
		List<SemanticRetrievalDocument> stale = staleDocuments(documents, identity);
		if (stale.isEmpty()) {
			return new IndexingResult(0, registry != null);
		}
		int indexed = 0;
		try {
			for (int offset = 0; offset < stale.size(); offset += EMBEDDING_BATCH_SIZE) {
				List<SemanticRetrievalDocument> batch = stale.subList(offset,
						Math.min(stale.size(), offset + EMBEDDING_BATCH_SIZE));
				List<float[]> vectors = EmbeddingModelSupport.embedTexts(embeddingModel,
						batch.stream().map(SemanticRetrievalDocument::semanticText).toList());
				if (vectors.size() != batch.size()) {
					throw new IllegalStateException("Embedding model returned " + vectors.size() + " vectors for "
							+ batch.size() + " Semantic Catalog documents");
				}
				if (registry == null) {
					int dimension = requireDimension(vectors.get(0));
					register(identity, dimension);
					registry = registry()
						.orElseThrow(() -> new IllegalStateException("Semantic embedding registry was not created"));
					assertCompatible(identity, registry);
					ensureHnswIndex(registry.dimension());
				}
				for (int index = 0; index < batch.size(); index++) {
					float[] vector = vectors.get(index);
					if (requireDimension(vector) != registry.dimension()) {
						throw new EmbeddingReindexRequiredException("Semantic Catalog embedding dimension changed from "
								+ registry.dimension() + " to " + vector.length + "; explicit reindex is required");
					}
					upsertEmbedding(batch.get(index), identity, vector);
					indexed++;
				}
			}
			return new IndexingResult(indexed, true);
		}
		catch (EmbeddingReindexRequiredException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("Unable to build Semantic Catalog embeddings; Exact/BM25 retrieval remains available", ex);
			return new IndexingResult(indexed, false);
		}
	}

	public Map<String, Double> vectorScores(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, int limit) {
		if (embeddingModel == null || projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(query) || limit <= 0) {
			return Map.of();
		}
		RegistryEntry registry = registry().orElse(null);
		if (registry == null) {
			return Map.of();
		}
		ConfiguredIdentity identity = configuredIdentity();
		try {
			assertCompatible(identity, registry);
		}
		catch (EmbeddingReindexRequiredException ex) {
			log.warn(
					"Semantic Catalog vector index requires explicit reindex; Exact/BM25 retrieval remains available: {}",
					ex.getMessage());
			return Map.of();
		}
		float[] queryVector;
		try {
			List<float[]> vectors = EmbeddingModelSupport.embedTexts(embeddingModel, List.of(query));
			if (vectors.size() != 1 || vectors.get(0) == null || vectors.get(0).length == 0) {
				return Map.of();
			}
			queryVector = vectors.get(0);
		}
		catch (RuntimeException ex) {
			log.warn("Semantic query embedding is unavailable; Exact/BM25 retrieval remains available", ex);
			return Map.of();
		}
		if (queryVector.length != registry.dimension()) {
			log.warn(
					"Semantic query embedding dimension is {} but active Semantic Catalog index dimension is {}; "
							+ "explicit reindex is required; Exact/BM25 retrieval remains available",
					queryVector.length, registry.dimension());
			return Map.of();
		}
		return queryVector(projectId, projectVersionId, catalogHash, queryVector, identity, registry, scope, limit);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public synchronized int reindexAll() {
		if (embeddingModel == null) {
			return 0;
		}
		List<SemanticRetrievalDocument> documents = jdbc.queryForList("""
				SELECT * FROM qw_semantic_retrieval_document ORDER BY project_version_id, document_type, asset_key
				""").stream().map(this::mapDocument).toList();
		if (documents.isEmpty()) {
			return 0;
		}
		ConfiguredIdentity identity = configuredIdentity();
		List<PreparedEmbedding> prepared = prepareReindexEmbeddings(documents);
		if (prepared.size() != documents.size()) {
			throw new IllegalStateException("Semantic Catalog reindex prepared " + prepared.size() + " vectors for "
					+ documents.size() + " documents");
		}
		int dimension = requireDimension(prepared.get(0).vector());
		if (prepared.stream().anyMatch(value -> requireDimension(value.vector()) != dimension)) {
			throw new IllegalStateException("Semantic Catalog reindex produced inconsistent embedding dimensions");
		}

		// Only switch the active identity after every new vector has been generated successfully. A provider timeout
		// therefore leaves the previous active index intact instead of deleting the last known-good retrieval channel.
		jdbc.execute("DROP INDEX IF EXISTS idx_qw_semantic_retrieval_embedding_hnsw");
		for (PreparedEmbedding value : prepared) {
			upsertEmbedding(value.document(), identity, value.vector());
		}
		activateRegistry(identity, dimension);
		jdbc.update("DELETE FROM qw_semantic_retrieval_embedding WHERE embedding_model <> ? OR embedding_version <> ?",
				identity.model(), identity.version());
		ensureHnswIndex(dimension);
		return prepared.size();
	}

	private List<PreparedEmbedding> prepareReindexEmbeddings(List<SemanticRetrievalDocument> documents) {
		List<PreparedEmbedding> prepared = new ArrayList<>(documents.size());
		for (int offset = 0; offset < documents.size(); offset += REINDEX_BATCH_SIZE) {
			List<SemanticRetrievalDocument> batch = documents.subList(offset,
					Math.min(documents.size(), offset + REINDEX_BATCH_SIZE));
			List<float[]> vectors = embedReindexBatch(batch);
			if (vectors.size() != batch.size()) {
				throw new IllegalStateException("Embedding model returned " + vectors.size() + " vectors for " + batch.size()
						+ " Semantic Catalog reindex documents");
			}
			for (int index = 0; index < batch.size(); index++) {
				prepared.add(new PreparedEmbedding(batch.get(index), vectors.get(index)));
			}
		}
		return List.copyOf(prepared);
	}

	private List<float[]> embedReindexBatch(List<SemanticRetrievalDocument> batch) {
		try {
			return EmbeddingModelSupport.embedTexts(embeddingModel,
					batch.stream().map(SemanticRetrievalDocument::semanticText).toList());
		}
		catch (RuntimeException failure) {
			if (batch.size() <= 1) {
				throw failure;
			}
			int middle = batch.size() / 2;
			List<float[]> left = embedReindexBatch(batch.subList(0, middle));
			List<float[]> right = embedReindexBatch(batch.subList(middle, batch.size()));
			List<float[]> combined = new ArrayList<>(left.size() + right.size());
			combined.addAll(left);
			combined.addAll(right);
			return List.copyOf(combined);
		}
	}

	public void assertConfiguredIndexCompatible() {
		if (embeddingModel == null) {
			return;
		}
		registry().ifPresent(value -> assertCompatible(configuredIdentity(), value));
	}

	/** Explicit Catalog -> retrieval-index consistency state used by release/activation gates. */
	public IndexReadiness readiness(Long projectId, Long projectVersionId, String catalogHash) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_FAILED, 0, 0, "missing catalog identity");
		}
		Integer documentCount = jdbc.queryForObject("""
				SELECT COUNT(*) FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				""", Integer.class, projectId, projectVersionId, catalogHash);
		int documents = documentCount == null ? 0 : documentCount;
		if (documents <= 0) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_PENDING, 0, 0, "retrieval documents are not built");
		}
		if (embeddingModel == null) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_READY, documents, 0,
					"lexical retrieval ready; no embedding model is configured");
		}
		try {
			ConfiguredIdentity identity = configuredIdentity();
			RegistryEntry registry = registry().orElse(null);
			if (registry == null) {
				return new IndexReadiness(IndexReadinessStatus.INDEX_PENDING, documents, 0,
						"embedding registry is not initialized");
			}
			assertCompatible(identity, registry);
			Integer vectorCount = jdbc.queryForObject("""
					SELECT COUNT(*)
					FROM qw_semantic_retrieval_document d
					JOIN qw_semantic_retrieval_embedding e ON e.document_id = d.id
					WHERE d.project_id = ? AND d.project_version_id = ? AND d.catalog_hash = ?
					  AND e.embedding_model = ? AND e.embedding_version = ?
					  AND e.dimension = ? AND e.content_hash = d.content_hash
					""", Integer.class, projectId, projectVersionId, catalogHash, identity.model(), identity.version(),
					registry.dimension());
			int vectors = vectorCount == null ? 0 : vectorCount;
			if (vectors != documents) {
				return new IndexReadiness(IndexReadinessStatus.INDEX_BUILDING, documents, vectors,
						"embedding coverage is incomplete");
			}
			return new IndexReadiness(IndexReadinessStatus.INDEX_READY, documents, vectors, "catalog index is aligned");
		}
		catch (RuntimeException ex) {
			return new IndexReadiness(IndexReadinessStatus.INDEX_FAILED, documents, 0, ex.getMessage());
		}
	}

	public void assertReady(Long projectId, Long projectVersionId, String catalogHash) {
		IndexReadiness readiness = readiness(projectId, projectVersionId, catalogHash);
		if (readiness.status() != IndexReadinessStatus.INDEX_READY) {
			throw new IllegalStateException("Semantic retrieval index is not query-ready: " + readiness.status() + " ("
					+ readiness.detail() + ", documents=" + readiness.documentCount() + ", vectors="
					+ readiness.vectorCount() + ")");
		}
	}

	public enum IndexReadinessStatus {
		INDEX_PENDING,
		INDEX_BUILDING,
		INDEX_READY,
		INDEX_FAILED
	}

	public record IndexReadiness(IndexReadinessStatus status, int documentCount, int vectorCount, String detail) {
	}

	private List<SemanticRetrievalDocument> staleDocuments(List<SemanticRetrievalDocument> documents,
			ConfiguredIdentity identity) {
		List<String> ids = documents.stream().map(SemanticRetrievalDocument::id).distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>(ids);
		args.add(identity.model());
		args.add(identity.version());
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT document_id, content_hash
				FROM qw_semantic_retrieval_embedding
				WHERE document_id IN (%s) AND embedding_model = ? AND embedding_version = ?
				""".formatted(placeholders(ids.size())), args.toArray())) {
			hashes.put(Objects.toString(row.get("document_id")), Objects.toString(row.get("content_hash")));
		}
		return documents.stream()
			.filter(document -> !Objects.equals(hashes.get(document.id()), document.contentHash()))
			.toList();
	}

	private void upsertEmbedding(SemanticRetrievalDocument document, ConfiguredIdentity identity, float[] vector) {
		jdbc.update("""
				INSERT INTO qw_semantic_retrieval_embedding
				(document_id, embedding_model, embedding_version, content_hash, dimension, embedding, update_time)
				VALUES (?, ?, ?, ?, ?, ?::vector, CURRENT_TIMESTAMP)
				ON CONFLICT (document_id, embedding_model, embedding_version)
				DO UPDATE SET content_hash = EXCLUDED.content_hash,
				              dimension = EXCLUDED.dimension,
				              embedding = EXCLUDED.embedding,
				              update_time = CURRENT_TIMESTAMP
				""", document.id(), identity.model(), identity.version(), document.contentHash(), vector.length,
				vectorLiteral(vector));
	}

	private Map<String, Double> queryVector(Long projectId, Long projectVersionId, String catalogHash,
			float[] queryVector, ConfiguredIdentity identity, RegistryEntry registry,
			SemanticRetrievalScope requestedScope, int limit) {
		SemanticRetrievalScope scope = requestedScope == null ? SemanticRetrievalScope.all() : requestedScope;
		StringBuilder where = new StringBuilder("""
				WHERE d.project_id = ? AND d.project_version_id = ? AND d.catalog_hash = ?
				  AND e.embedding_model = ? AND e.embedding_version = ? AND e.dimension = ?
				  AND e.content_hash = d.content_hash
				""");
		List<Object> args = new ArrayList<>(List.of(projectId, projectVersionId, catalogHash, identity.model(),
				identity.version(), registry.dimension()));
		if (scope.datasourceId() != null) {
			where.append(" AND d.datasource_id = ?");
			args.add(scope.datasourceId());
		}
		if (!scope.modelCodes().isEmpty()) {
			where.append(" AND d.model_code IN (").append(placeholders(scope.modelCodes().size())).append(')');
			args.addAll(scope.modelCodes());
		}
		if (!scope.documentTypes().isEmpty()) {
			where.append(" AND d.document_type IN (").append(placeholders(scope.documentTypes().size())).append(')');
			args.addAll(scope.documentTypes().stream().map(Enum::name).toList());
		}
		if (!scope.assetKeys().isEmpty()) {
			where.append(" AND d.asset_key IN (").append(placeholders(scope.assetKeys().size())).append(')');
			args.addAll(scope.assetKeys());
		}
		String vector = vectorLiteral(queryVector);
		String distanceType = distanceType(registry.dimension());
		String sql = """
				SELECT d.id, 1 - ((e.embedding::%s) <=> (?::%s)) AS similarity
				FROM qw_semantic_retrieval_embedding e
				JOIN qw_semantic_retrieval_document d ON d.id = e.document_id
				%s
				ORDER BY (e.embedding::%s) <=> (?::%s)
				LIMIT ?
				""".formatted(distanceType, distanceType, where, distanceType, distanceType);
		List<Object> queryArgs = new ArrayList<>();
		queryArgs.add(vector);
		queryArgs.addAll(args);
		queryArgs.add(vector);
		queryArgs.add(Math.min(Math.max(limit, 1), 500));
		Map<String, Double> result = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(sql, queryArgs.toArray())) {
			if (row.get("similarity") instanceof Number number) {
				result.put(Objects.toString(row.get("id")), Math.max(0d, number.doubleValue()));
			}
		}
		return result;
	}

	private Optional<RegistryEntry> registry() {
		return jdbc.queryForList("""
				SELECT embedding_model, embedding_version, dimension, status
				FROM qw_embedding_index_registry WHERE index_scope = ?
				""", INDEX_SCOPE)
			.stream()
			.findFirst()
			.map(row -> new RegistryEntry(Objects.toString(row.get("embedding_model")),
					Objects.toString(row.get("embedding_version")), ((Number) row.get("dimension")).intValue(),
					Objects.toString(row.get("status"))));
	}

	private void register(ConfiguredIdentity identity, int dimension) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO NOTHING
				""", INDEX_SCOPE, identity.model(), identity.version(), dimension);
	}

	private void activateRegistry(ConfiguredIdentity identity, int dimension) {
		jdbc.update("""
				INSERT INTO qw_embedding_index_registry
				(index_scope, embedding_model, embedding_version, dimension, status, active_since, update_time)
				VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (index_scope) DO UPDATE
				SET embedding_model = EXCLUDED.embedding_model,
				    embedding_version = EXCLUDED.embedding_version,
				    dimension = EXCLUDED.dimension,
				    status = 'ACTIVE',
				    active_since = CURRENT_TIMESTAMP,
				    update_time = CURRENT_TIMESTAMP
				""", INDEX_SCOPE, identity.model(), identity.version(), dimension);
	}

	private void assertCompatible(ConfiguredIdentity identity, RegistryEntry registry) {
		if (!"ACTIVE".equals(registry.status()) || !Objects.equals(identity.model(), registry.model())
				|| !Objects.equals(identity.version(), registry.version())) {
			throw new EmbeddingReindexRequiredException(
					"Active Semantic Catalog embedding index differs from configured embedding model; explicit reindex is required");
		}
	}

	private void ensureHnswIndex(int dimension) {
		if (dimension <= 0) {
			throw new IllegalArgumentException("Embedding dimension must be positive");
		}
		if (dimension > HALF_VECTOR_HNSW_MAX_DIMENSION) {
			log.warn("Semantic Catalog embedding dimension {} exceeds pgvector HNSW limits; vector recall remains exact-scan capable without HNSW",
					dimension);
			return;
		}
		String castType = distanceType(dimension);
		String operatorClass = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector_cosine_ops" : "halfvec_cosine_ops";
		jdbc.execute("CREATE INDEX IF NOT EXISTS idx_qw_semantic_retrieval_embedding_hnsw "
				+ "ON qw_semantic_retrieval_embedding USING hnsw ((embedding::" + castType + ") " + operatorClass + ")");
	}

	private String distanceType(int dimension) {
		String type = dimension <= VECTOR_HNSW_MAX_DIMENSION ? "vector" : "halfvec";
		return type + "(" + dimension + ")";
	}

	private ConfiguredIdentity configuredIdentity() {
		String model = embeddingModel.getClass().getName();
		LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
		identity.put("implementation", model);
		if (embeddingModelIdentityProvider != null) {
			var configured = embeddingModelIdentityProvider.currentEmbeddingIdentity().orElse(null);
			if (configured != null) {
				model = Objects.toString(configured.model(), model);
				identity.putAll(configured.attributes());
			}
		}
		return new ConfiguredIdentity(truncate(model, 255), canonicalJson.hash(identity));
	}

	private int requireDimension(float[] vector) {
		if (vector == null || vector.length == 0) {
			throw new IllegalStateException("Embedding model did not return a usable vector");
		}
		return vector.length;
	}

	private String vectorLiteral(float[] vector) {
		StringBuilder builder = new StringBuilder(vector.length * 12 + 2).append('[');
		for (int index = 0; index < vector.length; index++) {
			if (index > 0) {
				builder.append(',');
			}
			builder.append(Float.toString(vector[index]));
		}
		return builder.append(']').toString();
	}

	private SemanticRetrievalDocument mapDocument(Map<String, Object> row) {
		return new SemanticRetrievalDocument(Objects.toString(row.get("id")), number(row.get("project_id")),
				number(row.get("project_version_id")), Objects.toString(row.get("catalog_hash")),
				SemanticRetrievalDocument.DocumentType.valueOf(Objects.toString(row.get("document_type"))),
				Objects.toString(row.get("asset_type")), Objects.toString(row.get("asset_key")),
				row.get("datasource_id") instanceof Number value ? value.intValue() : null,
				Objects.toString(row.get("model_code")), Objects.toString(row.get("physical_table")),
				Objects.toString(row.get("lexical_text"), ""), Objects.toString(row.get("semantic_text"), ""),
				Objects.toString(row.get("source_fingerprint")), Objects.toString(row.get("content_hash")),
				Objects.toString(row.get("generator_model"), ""), Objects.toString(row.get("generator_version"), ""),
				Objects.toString(row.get("generation_status")));
	}

	private Long number(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	private String placeholders(int count) {
		return String.join(",", Collections.nCopies(count, "?"));
	}

	private String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	public record IndexingResult(int indexedDocuments, boolean vectorAvailable) {
	}

	private record PreparedEmbedding(SemanticRetrievalDocument document, float[] vector) {
	}

	private record ConfiguredIdentity(String model, String version) {
	}

	private record RegistryEntry(String model, String version, int dimension, String status) {
	}

	public static class EmbeddingReindexRequiredException extends IllegalStateException {

		public EmbeddingReindexRequiredException(String message) {
			super(message);
		}

	}

}
