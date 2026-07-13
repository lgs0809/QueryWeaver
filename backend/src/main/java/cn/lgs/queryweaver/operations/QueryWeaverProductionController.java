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
package cn.lgs.queryweaver.operations;

import cn.lgs.queryweaver.common.OperatorAuthorizationService;
import cn.lgs.queryweaver.common.OperatorContext;
import cn.lgs.queryweaver.common.OperatorRole;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.CanaryRequest;
import cn.lgs.queryweaver.project.application.ProjectRuntimeGate;
import cn.lgs.queryweaver.semantic.retrieval.SemanticRetrievalIndexService;
import cn.lgs.queryweaver.semantic.retrieval.SemanticRetrievalIndexService.IndexReadiness;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.CompletionRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.EpisodeRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.FeedbackRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.GoldenCaseRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.JobRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.ReleaseRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.ShadowResult;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.SqlTraceRequest;
import cn.lgs.queryweaver.operations.QueryWeaverProductionService.TraceRequest;
import cn.lgs.queryweaver.run.RuntimeMutationAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/queryweaver/operations")
@RequiredArgsConstructor
public class QueryWeaverProductionController {

	private final QueryWeaverProductionService service;

	private final OperatorContext.Resolver operatorResolver;

	private final OperatorAuthorizationService authorization;

	private final RuntimeMutationAuthorizationService runtimeMutationAuthorizationService;

	private final ProjectRuntimeGate projectRuntimeGate;

	private final SemanticRetrievalIndexService semanticRetrievalIndexService;

	@PostMapping("/episodes")
	public Map<String, Object> createEpisode(@RequestBody EpisodeRequest request, @RequestHeader HttpHeaders headers,
			Principal principal) {
		require(headers, principal, "operations-episode-create", OperatorRole.ADMIN);
		return service.createEpisode(request);
	}

	@GetMapping("/projects/{projectId}/episodes")
	public List<Map<String, Object>> episodes(@PathVariable Long projectId,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
		return service.listEpisodes(projectId, limit);
	}

	@PostMapping("/episodes/{episodeId}/attempts/{attemptNo}")
	public Map<String, Object> createAttempt(@PathVariable String episodeId, @PathVariable int attemptNo,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-attempt-create:" + episodeId, OperatorRole.ADMIN);
		return service.createAttempt(episodeId, attemptNo);
	}

	@PostMapping("/attempts/{attemptId}/node-traces")
	public Map<String, Object> nodeTrace(@PathVariable String attemptId, @RequestBody TraceRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-node-trace:" + attemptId, OperatorRole.ADMIN);
		return service.recordNodeTrace(attemptId, request);
	}

	@PostMapping("/attempts/{attemptId}/sql-traces")
	public Map<String, Object> sqlTrace(@PathVariable String attemptId, @RequestBody SqlTraceRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-sql-trace:" + attemptId, OperatorRole.ADMIN);
		return service.recordSqlTrace(attemptId, request);
	}

	@PostMapping("/episodes/{episodeId}/complete")
	public Map<String, Object> complete(@PathVariable String episodeId, @RequestBody CompletionRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-episode-complete:" + episodeId, OperatorRole.ADMIN);
		return service.completeEpisode(episodeId, request);
	}

	@PostMapping("/episodes/{episodeId}/feedback")
	public Map<String, Object> feedback(@PathVariable String episodeId, @RequestBody FeedbackRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = require(headers, principal, "operations-feedback:" + episodeId, OperatorRole.VIEWER);
		runtimeMutationAuthorizationService.requireEpisodeOwnerOrAdmin(episodeId, operator);
		return service.feedback(episodeId,
				new FeedbackRequest(operator.operator(), request.rating(), request.adopted(), request.comment()));
	}

	@PostMapping("/projects/{projectId}/golden-cases")
	public Map<String, Object> goldenCase(@PathVariable Long projectId, @RequestBody GoldenCaseRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-golden-case:" + projectId, OperatorRole.REVIEWER);
		return service.createGoldenCase(projectId, request);
	}

	@GetMapping("/projects/{projectId}/golden-cases")
	public List<Map<String, Object>> goldenCases(@PathVariable Long projectId) {
		return service.listGoldenCases(projectId);
	}

	@PostMapping("/projects/{projectId}/jobs")
	public Map<String, Object> createJob(@PathVariable Long projectId, @RequestBody JobRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "operations-job-create:" + projectId, OperatorRole.EDITOR);
		return service.createJob(projectId, request);
	}

	@GetMapping("/jobs/{jobId}")
	public Map<String, Object> job(@PathVariable String jobId) {
		return service.job(jobId);
	}

	@GetMapping("/projects/{projectId}/jobs")
	public List<Map<String, Object>> jobs(@PathVariable Long projectId,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
		return service.listJobs(projectId, limit);
	}

	@PostMapping("/jobs/{jobId}/retry")
	public Map<String, Object> retryJob(@PathVariable String jobId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		require(headers, principal, "operations-job-retry:" + jobId, OperatorRole.EDITOR);
		return service.retryJob(jobId);
	}

	@PostMapping("/jobs/{jobId}/cancel")
	public Map<String, Object> cancelJob(@PathVariable String jobId, @RequestHeader HttpHeaders headers,
			Principal principal) {
		require(headers, principal, "operations-job-cancel:" + jobId, OperatorRole.EDITOR);
		return service.cancelJob(jobId);
	}

	@PostMapping("/projects/{projectId}/releases")
	public Map<String, Object> release(@PathVariable Long projectId, @RequestBody ReleaseRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.createRelease(projectId, request,
				require(headers, principal, "release-create:" + projectId, OperatorRole.PUBLISHER));
	}

	@PostMapping("/releases/{releaseId}/shadow-results")
	public Map<String, Object> shadow(@PathVariable String releaseId, @RequestBody ShadowResult request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.recordShadow(releaseId, request,
				require(headers, principal, "release-shadow:" + releaseId, OperatorRole.PUBLISHER));
	}

	@GetMapping("/projects/{projectId}/releases")
	public List<Map<String, Object>> releases(@PathVariable Long projectId) {
		return service.listReleases(projectId);
	}

	@GetMapping("/releases/{releaseId}/assignment")
	public Map<String, Object> assignment(@PathVariable String releaseId, @RequestParam String requestId) {
		return Map.of("projectVersionId", service.assignVersion(releaseId, requestId));
	}

	@PostMapping("/releases/{releaseId}/canary")
	public Map<String, Object> canary(@PathVariable String releaseId, @RequestBody CanaryRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.advanceCanary(releaseId, request,
				require(headers, principal, "release-canary:" + releaseId, OperatorRole.PUBLISHER));
	}

	@PostMapping("/releases/{releaseId}/rollback")
	public Map<String, Object> rollback(@PathVariable String releaseId, @Valid @RequestBody RollbackRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		return service.rollback(releaseId, request.reason(),
				require(headers, principal, "release-rollback:" + releaseId, OperatorRole.PUBLISHER));
	}

	@GetMapping("/projects/{projectId}/dashboard")
	public Map<String, Object> dashboard(@PathVariable Long projectId) {
		return service.dashboard(projectId);
	}

	@GetMapping("/projects/{projectId}/semantic-index")
	public IndexReadiness semanticIndex(@PathVariable Long projectId) {
		var context = projectRuntimeGate.requireReadyByProject(projectId);
		return semanticRetrievalIndexService.readiness(context.projectId(), context.projectVersionId(),
				context.catalogHash());
	}

	@PostMapping("/semantic-index/reindex")
	public Mono<Map<String, Object>> reindexSemanticIndex(@RequestHeader HttpHeaders headers, Principal principal) {
		require(headers, principal, "semantic-index-reindex", OperatorRole.ADMIN);
		return Mono.fromCallable(() -> Map.<String, Object>of("indexedEmbeddings",
				semanticRetrievalIndexService.reindexAll())).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/cache")
	public SemanticCatalogCache.CacheStats cache() {
		return service.cacheStats();
	}

	private OperatorContext require(HttpHeaders headers, Principal principal, String operation,
			OperatorRole requiredRole) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, operation);
		authorization.requireAtLeast(operator, requiredRole, operation);
		return operator;
	}

	public record RollbackRequest(@NotBlank String reason) {
	}

}
