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
package cn.lgs.queryweaver.clarification;

import cn.lgs.queryweaver.project.domain.SemanticProjectRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticAssetStatus;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Ensures durable language bindings only point at enabled assets in the pinned Project
 * Version.
 */
@Service
public class SemanticBindingTargetValidator {

	private final SemanticProjectRepository projectRepository;

	private final SemanticCatalogRepository catalogRepository;

	public SemanticBindingTargetValidator(SemanticProjectRepository projectRepository,
			SemanticCatalogRepository catalogRepository) {
		this.projectRepository = projectRepository;
		this.catalogRepository = catalogRepository;
	}

	public void requireActiveAsset(Long projectId, String assetType, String assetKey) {
		if (projectId == null) {
			throw new IllegalArgumentException("projectId is required");
		}
		var project = projectRepository.findProject(projectId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project not found: " + projectId));
		Long activeVersionId = project.getActiveVersionId();
		if (activeVersionId == null) {
			throw new IllegalStateException("Project has no active Semantic Catalog for durable language bindings");
		}
		requireAsset(projectId, activeVersionId, assetType, assetKey);
	}

	public void requireAsset(Long projectId, Long projectVersionId, String assetType, String assetKey) {
		if (projectId == null || projectVersionId == null) {
			throw new IllegalArgumentException("projectId and projectVersionId are required");
		}
		var version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + projectVersionId));
		if (!Objects.equals(projectId, version.getProjectId())) {
			throw new IllegalArgumentException("Semantic project version does not belong to project: " + projectId);
		}
		SemanticCatalogSnapshot catalog = catalogRepository.loadCatalog(projectId, projectVersionId);
		boolean exists = switch (assetType) {
			case "METRIC" -> catalog.getMetrics()
				.stream()
				.anyMatch(value -> value.getStatus() == SemanticAssetStatus.ENABLED
						&& Objects.equals(value.getMetricCode(), assetKey));
			case "DIMENSION" -> catalog.getDimensions()
				.stream()
				.anyMatch(value -> value.getStatus() == SemanticAssetStatus.ENABLED
						&& Objects.equals(value.getDimensionCode(), assetKey));
			case "ENUM_VALUE" -> enumValueExists(catalog, assetKey);
			case "TIME_COLUMN" -> timeColumnExists(catalog, assetKey);
			default -> false;
		};
		if (!exists) {
			throw new IllegalArgumentException("Durable semantic binding target does not exist in Project Version "
					+ projectVersionId + ": " + assetType + " " + assetKey);
		}
	}

	private boolean timeColumnExists(SemanticCatalogSnapshot catalog, String assetKey) {
		String[] parts = assetKey == null ? new String[0] : assetKey.split(":", 2);
		if (parts.length != 2) {
			return false;
		}
		return catalog.getColumns()
			.stream()
			.anyMatch(value -> value.getStatus() == SemanticAssetStatus.ENABLED
					&& value.getRole() == cn.lgs.queryweaver.semantic.domain.SemanticColumnRole.TIME
					&& Objects.equals(value.getModelCode(), parts[0]) && Objects.equals(value.getColumnName(), parts[1]));
	}

	private boolean enumValueExists(SemanticCatalogSnapshot catalog, String assetKey) {
		String[] parts = assetKey == null ? new String[0] : assetKey.split(":", 3);
		if (parts.length != 3) {
			return false;
		}
		return catalog.getEnumValues()
			.stream()
			.anyMatch(value -> value.getStatus() == SemanticAssetStatus.ENABLED
					&& Objects.equals(value.getModelCode(), parts[0]) && Objects.equals(value.getColumnName(), parts[1])
					&& Objects.equals(value.getValueCode(), parts[2]));
	}

}
