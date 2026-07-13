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
package cn.lgs.queryweaver.conversation;

import cn.lgs.queryweaver.common.OperatorContext;
import cn.lgs.queryweaver.conversation.ProjectConversationService.ConversationView;
import cn.lgs.queryweaver.conversation.ProjectConversationService.HumanReviewCommand;
import cn.lgs.queryweaver.conversation.ProjectConversationService.ProjectConversation;
import cn.lgs.queryweaver.conversation.ProjectConversationService.ProjectMessage;
import cn.lgs.queryweaver.conversation.ProjectConversationService.SendMessageCommand;
import cn.lgs.queryweaver.conversation.ProjectConversationService.SendMessageResult;
import cn.lgs.queryweaver.run.QueryRun;
import cn.lgs.queryweaver.run.RuntimeMutationAuthorizationService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/queryweaver/projects/{projectId}/conversations")
@RequiredArgsConstructor
public class ProjectConversationController {

	private final ProjectConversationService service;

	private final OperatorContext.Resolver operatorResolver;

	private final RuntimeMutationAuthorizationService runtimeMutationAuthorizationService;

	@GetMapping
	public List<ProjectConversation> list(@PathVariable Long projectId) {
		return service.list(projectId);
	}

	@PostMapping
	public ProjectConversation create(@PathVariable Long projectId, @RequestBody CreateConversationRequest request,
			@RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "project-conversation-create");
		return service.create(projectId, request.title(), operator.operator());
	}

	@GetMapping("/{conversationId}")
	public ConversationView view(@PathVariable Long projectId, @PathVariable String conversationId) {
		return service.view(projectId, conversationId);
	}

	@PutMapping("/{conversationId}")
	public ProjectConversation rename(@PathVariable Long projectId, @PathVariable String conversationId,
			@RequestBody RenameConversationRequest request) {
		return service.rename(projectId, conversationId, request.revision(), request.title());
	}

	@PostMapping("/{conversationId}/archive")
	public ProjectConversation archive(@PathVariable Long projectId, @PathVariable String conversationId,
			@RequestBody ConversationRevisionRequest request) {
		return service.archive(projectId, conversationId, request.revision());
	}

	@PostMapping("/{conversationId}/messages")
	public Mono<SendMessageResult> send(@PathVariable Long projectId, @PathVariable String conversationId,
			@RequestBody SendMessageCommand command, @RequestHeader HttpHeaders headers, Principal principal) {
		return Mono.fromCallable(() -> {
			OperatorContext operator = operatorResolver.resolve(headers, principal, "project-conversation-send");
			return service.send(projectId, conversationId, command, operator.operator());
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/{conversationId}/runs/{runId}/human-review")
	public QueryRun humanReview(@PathVariable Long projectId, @PathVariable String conversationId,
			@PathVariable String runId, @RequestBody HumanReviewCommand command, @RequestHeader HttpHeaders headers,
			Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "project-conversation-review:" + runId);
		runtimeMutationAuthorizationService.requireRunOwnerOrAdmin(runId, operator);
		return service.submitHumanReview(projectId, conversationId, runId, command);
	}

	@PostMapping("/{conversationId}/runs/{runId}/sync")
	public ProjectMessage synchronize(@PathVariable Long projectId, @PathVariable String conversationId,
			@PathVariable String runId, @RequestHeader HttpHeaders headers, Principal principal) {
		OperatorContext operator = operatorResolver.resolve(headers, principal, "project-conversation-sync:" + runId);
		runtimeMutationAuthorizationService.requireRunOwnerOrAdmin(runId, operator);
		return service.synchronizeAssistantMessage(projectId, conversationId, runId);
	}

	public record CreateConversationRequest(String title) {
	}

	public record RenameConversationRequest(long revision, String title) {
	}

	public record ConversationRevisionRequest(long revision) {
	}

}
