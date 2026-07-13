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
package cn.lgs.queryweaver.semantic.adapter;

import cn.lgs.queryweaver.project.domain.ProjectVersionCatalogReadiness.CatalogReadiness;
import cn.lgs.queryweaver.semantic.application.DatabaseSemanticCatalogAnalyzer;
import cn.lgs.queryweaver.semantic.application.DatabaseSemanticCatalogAnalyzer.AnalysisResult;
import cn.lgs.queryweaver.learning.QueryCaseHints;
import cn.lgs.queryweaver.semantic.application.LlmSemanticPlanningService;
import cn.lgs.queryweaver.semantic.application.SemanticCatalogApplicationService;
import cn.lgs.queryweaver.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.queryweaver.semantic.domain.SemanticQueryPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queryweaver/projects/{projectId}/versions/{versionId}/semantic-catalog")
@RequiredArgsConstructor
public class SemanticCatalogController {

	private final SemanticCatalogApplicationService catalogService;

	private final LlmSemanticPlanningService llmSemanticPlanningService;

	private final DatabaseSemanticCatalogAnalyzer databaseAnalyzer;

	@GetMapping
	public SemanticCatalogSnapshot getCatalog(@PathVariable Long projectId, @PathVariable Long versionId) {
		return catalogService.getCatalog(projectId, versionId);
	}

	@PutMapping
	public SemanticCatalogSnapshot replaceCatalog(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestBody SemanticCatalogSnapshot snapshot) {
		return catalogService.replaceDraftCatalog(projectId, versionId, snapshot);
	}

	@PostMapping("/scan-database")
	public AnalysisResult scanDatabase(@PathVariable Long projectId, @PathVariable Long versionId,
			@Valid @RequestBody ScanDatabaseRequest request) throws Exception {
		return databaseAnalyzer.analyze(projectId, versionId, request.datasourceId(), request.tables());
	}

	@GetMapping("/readiness")
	public CatalogReadiness readiness(@PathVariable Long projectId, @PathVariable Long versionId) {
		return catalogService.assess(projectId, versionId);
	}

	@PostMapping("/query-plan")
	public SemanticQueryPlan queryPlan(@PathVariable Long projectId, @PathVariable Long versionId,
			@RequestBody QueryPlanRequest request) {
		List<String> selectedTables = request.selectedPhysicalTables() == null ? List.of()
				: List.copyOf(request.selectedPhysicalTables());
		QueryCaseHints bindings = llmSemanticPlanningService.plan(projectId, versionId, request.canonicalQuery(),
				selectedTables, List.of());
		return catalogService.buildQueryPlan(projectId, versionId, request.canonicalQuery(), selectedTables, bindings);
	}

	public record ScanDatabaseRequest(@NotNull Integer datasourceId, List<String> tables) {
	}

	public record QueryPlanRequest(String canonicalQuery, List<String> selectedPhysicalTables) {
	}

}
