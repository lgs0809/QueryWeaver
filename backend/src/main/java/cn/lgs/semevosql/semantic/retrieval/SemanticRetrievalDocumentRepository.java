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
package cn.lgs.semevosql.semantic.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence boundary for derived Semantic Catalog retrieval documents. */
@Repository
public class SemanticRetrievalDocumentRepository {

	private final JdbcTemplate jdbc;

	public SemanticRetrievalDocumentRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<SemanticRetrievalDocument> findExisting(Long projectVersionId,
			SemanticRetrievalDocument.DocumentType documentType, String assetKey) {
		return jdbc.queryForList("""
				SELECT *
				FROM qw_semantic_retrieval_document
				WHERE project_version_id = ? AND document_type = ? AND asset_key = ?
				""", projectVersionId, documentType.name(), assetKey).stream().findFirst().map(this::map);
	}

	public List<SemanticRetrievalDocument> findCatalog(Long projectId, Long projectVersionId, String catalogHash) {
		return jdbc.queryForList("""
				SELECT *
				FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				ORDER BY document_type, asset_key
				""", projectId, projectVersionId, catalogHash).stream().map(this::map).toList();
	}

	public List<SemanticRetrievalDocument> findVersion(Long projectId, Long projectVersionId) {
		return jdbc.queryForList("""
				SELECT *
				FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ?
				ORDER BY document_type, asset_key
				""", projectId, projectVersionId).stream().map(this::map).toList();
	}

	public void upsert(SemanticRetrievalDocument document) {
		try {
			jdbc.update("""
					INSERT INTO qw_semantic_retrieval_document
					(id, project_id, project_version_id, catalog_hash, document_type, asset_type, asset_key,
					 datasource_id, model_code, physical_table, lexical_text, semantic_text, source_fingerprint,
					 content_hash, generator_model, generator_version, generation_status, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
					ON CONFLICT (project_version_id, document_type, asset_key)
					DO UPDATE SET catalog_hash = EXCLUDED.catalog_hash,
					              asset_type = EXCLUDED.asset_type,
					              datasource_id = EXCLUDED.datasource_id,
					              model_code = EXCLUDED.model_code,
					              physical_table = EXCLUDED.physical_table,
					              lexical_text = EXCLUDED.lexical_text,
					              semantic_text = EXCLUDED.semantic_text,
					              source_fingerprint = EXCLUDED.source_fingerprint,
					              content_hash = EXCLUDED.content_hash,
					              generator_model = EXCLUDED.generator_model,
					              generator_version = EXCLUDED.generator_version,
					              generation_status = EXCLUDED.generation_status,
					              update_time = CURRENT_TIMESTAMP
					""", document.id(), document.projectId(), document.projectVersionId(), document.catalogHash(),
					document.documentType().name(), document.assetType(), document.assetKey(), document.datasourceId(),
					document.modelCode(), document.physicalTable(), document.lexicalText(), document.semanticText(),
					document.sourceFingerprint(), document.contentHash(), document.generatorModel(),
					document.generatorVersion(), document.generationStatus());
		}
		catch (DuplicateKeyException ex) {
			if (updateSameLogicalDocument(document) != 1) {
				throw ex;
			}
		}
	}

	private int updateSameLogicalDocument(SemanticRetrievalDocument document) {
		return jdbc.update("""
				UPDATE qw_semantic_retrieval_document
				SET catalog_hash = ?,
				    asset_type = ?,
				    datasource_id = ?,
				    model_code = ?,
				    physical_table = ?,
				    lexical_text = ?,
				    semantic_text = ?,
				    source_fingerprint = ?,
				    content_hash = ?,
				    generator_model = ?,
				    generator_version = ?,
				    generation_status = ?,
				    update_time = CURRENT_TIMESTAMP
				WHERE id = ?
				  AND project_id = ?
				  AND project_version_id = ?
				  AND document_type = ?
				  AND asset_key = ?
				""", document.catalogHash(), document.assetType(), document.datasourceId(), document.modelCode(),
				document.physicalTable(), document.lexicalText(), document.semanticText(), document.sourceFingerprint(),
				document.contentHash(), document.generatorModel(), document.generatorVersion(),
				document.generationStatus(), document.id(), document.projectId(), document.projectVersionId(),
				document.documentType().name(), document.assetKey());
	}

	public void deleteStale(Long projectVersionId, String catalogHash, List<String> activeDocumentIds) {
		jdbc.update("DELETE FROM qw_semantic_retrieval_document WHERE project_version_id = ? AND catalog_hash <> ?",
				projectVersionId, catalogHash);
		if (activeDocumentIds == null || activeDocumentIds.isEmpty()) {
			jdbc.update("DELETE FROM qw_semantic_retrieval_document WHERE project_version_id = ? AND catalog_hash = ?",
					projectVersionId, catalogHash);
			return;
		}
		List<Object> args = new ArrayList<>(List.of(projectVersionId, catalogHash));
		args.addAll(activeDocumentIds);
		jdbc.update("DELETE FROM qw_semantic_retrieval_document WHERE project_version_id = ? AND catalog_hash = ? "
				+ "AND id NOT IN (" + placeholders(activeDocumentIds.size()) + ")", args.toArray());
	}

	public void assertCatalogVersion(Long projectId, Long projectVersionId, String catalogHash) {
		Integer expected = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash = ?
				""", Integer.class, projectId, projectVersionId, catalogHash);
		Integer mismatched = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM qw_semantic_retrieval_document
				WHERE project_id = ? AND project_version_id = ? AND catalog_hash <> ?
				""", Integer.class, projectId, projectVersionId, catalogHash);
		if (expected == null || expected <= 0 || (mismatched != null && mismatched > 0)) {
			throw new IllegalStateException("Semantic retrieval documents are not aligned with Catalog version "
					+ projectVersionId + " and hash " + catalogHash);
		}
	}

	private SemanticRetrievalDocument map(Map<String, Object> row) {
		return new SemanticRetrievalDocument(Objects.toString(row.get("id")), number(row.get("project_id")),
				number(row.get("project_version_id")), Objects.toString(row.get("catalog_hash")),
				SemanticRetrievalDocument.DocumentType.valueOf(Objects.toString(row.get("document_type"))),
				Objects.toString(row.get("asset_type")), Objects.toString(row.get("asset_key")),
				row.get("datasource_id") instanceof Number number ? number.intValue() : null,
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

}
