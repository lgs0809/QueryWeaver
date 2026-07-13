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
package cn.lgs.queryweaver.project.security;

import cn.lgs.queryweaver.common.OperatorContext;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Applies project membership checks after JWT authentication and before controllers. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queryweaver.security.enabled", havingValue = "true")
public class ProjectAccessWebFilter implements WebFilter {

	private static final Pattern PROJECT = Pattern.compile("^/api/queryweaver/projects/(\\d+)(?:/.*)?$");

	private static final Pattern PROJECT_CONVERSATION_CREATE = Pattern
		.compile("^/api/queryweaver/projects/(\\d+)/conversations$");

	private static final Pattern PROJECT_CONVERSATION_SEND = Pattern
		.compile("^/api/queryweaver/projects/(\\d+)/conversations/[^/]+/messages$");

	private static final Pattern PROJECT_CONVERSATION_RUN_ACTION = Pattern
		.compile("^/api/queryweaver/projects/(\\d+)/conversations/[^/]+/runs/[^/]+/(?:human-review|sync)$");

	private static final Pattern QUERY_BINDING_CORRECTION = Pattern.compile(
			"^/api/queryweaver/projects/(\\d+)/conversations/[^/]+/runs/[^/]+/corrections/binding$");

	private static final Pattern OPERATIONS_PROJECT = Pattern
		.compile("^/api/queryweaver/operations/projects/(\\d+)(?:/.*)?$");

	private static final Pattern RUN = Pattern.compile("^/api/queryweaver/runs/([^/]+)(?:/.*)?$");

	private static final Pattern GAP = Pattern.compile("^/api/queryweaver/gaps/(\\d+)(?:/.*)?$");

	private static final Pattern EVOLUTION_CANDIDATE = Pattern
		.compile("^/api/queryweaver/semantic-evolution/candidates/([^/]+)(?:/.*)?$");

	private static final Pattern REPLAY_RUN = Pattern
		.compile("^/api/queryweaver/semantic-evolution/replay-runs/([^/]+)(?:/.*)?$");

	private static final Pattern OPTIMIZATION_CANDIDATE = Pattern
		.compile("^/api/queryweaver/runtime-optimization/candidates/([^/]+)(?:/.*)?$");

	private static final Pattern JOB = Pattern.compile("^/api/queryweaver/operations/jobs/([^/]+)(?:/.*)?$");

	private static final Pattern RELEASE = Pattern.compile("^/api/queryweaver/operations/releases/([^/]+)(?:/.*)?$");

	private static final Pattern EPISODE = Pattern.compile("^/api/queryweaver/operations/episodes/([^/]+)(?:/.*)?$");

	private static final Pattern ATTEMPT = Pattern.compile("^/api/queryweaver/operations/attempts/([^/]+)(?:/.*)?$");

	private static final Pattern TRAJECTORY_PATTERN = Pattern
		.compile("^/api/queryweaver/trajectory/patterns/([^/]+)(?:/.*)?$");

	private final ProjectAccessService accessService;

	private final OperatorContext.Resolver operatorResolver;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (!path.startsWith("/api/queryweaver/")) {
			return chain.filter(exchange);
		}
		ProjectAccessRole required = requiredRole(path, exchange.getRequest().getMethod());
		return exchange.getPrincipal()
			.switchIfEmpty(Mono.error(new MissingPrincipalException()))
			.flatMap(principal -> authorize(exchange, path, principal, required))
			.flatMap(authorized -> authorized ? chain.filter(exchange) : Mono.defer(() -> chain.filter(exchange)))
			.onErrorResume(MissingPrincipalException.class,
					ex -> reject(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED"))
			.onErrorResume(SecurityException.class,
					ex -> reject(exchange, HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED"));
	}

	private ProjectAccessRole requiredRole(String path, HttpMethod method) {
		if (readOnly(method) || viewerRuntimeMutation(path, method)) {
			return ProjectAccessRole.VIEWER;
		}
		return ProjectAccessRole.EDITOR;
	}

	private boolean viewerRuntimeMutation(String path, HttpMethod method) {
		if (method != HttpMethod.POST) {
			return false;
		}
		return PROJECT_CONVERSATION_CREATE.matcher(path).matches()
				|| PROJECT_CONVERSATION_SEND.matcher(path).matches()
				|| PROJECT_CONVERSATION_RUN_ACTION.matcher(path).matches()
				|| QUERY_BINDING_CORRECTION.matcher(path).matches()
				|| path.matches("^/api/queryweaver/operations/episodes/[^/]+/feedback$")
				|| path.matches("^/api/queryweaver/runs/[^/]+/(?:cancel|resume)$")
				|| path.matches("^/api/queryweaver/runs/[^/]+/clarification/[^/]+/answer$");
	}

	private Mono<Boolean> authorize(ServerWebExchange exchange, String path, Principal principal,
			ProjectAccessRole required) {
		return Mono.fromCallable(() -> {
			Optional<Long> projectId = resolveProjectId(path);
			if (projectId.isEmpty()) {
				if (projectScopedResource(path)) {
					throw new SecurityException("Project ownership could not be resolved for protected resource");
				}
				return false;
			}
			OperatorContext operator = operatorResolver.resolve(exchange.getRequest().getHeaders(), principal,
					"project-access:" + projectId.get());
			accessService.requireAccess(projectId.get(), operator, required);
			return true;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private boolean projectScopedResource(String path) {
		return PROJECT.matcher(path).matches() || OPERATIONS_PROJECT.matcher(path).matches()
				|| RUN.matcher(path).matches() || GAP.matcher(path).matches()
				|| EVOLUTION_CANDIDATE.matcher(path).matches() || REPLAY_RUN.matcher(path).matches()
				|| OPTIMIZATION_CANDIDATE.matcher(path).matches() || JOB.matcher(path).matches()
				|| RELEASE.matcher(path).matches() || EPISODE.matcher(path).matches() || ATTEMPT.matcher(path).matches()
				|| TRAJECTORY_PATTERN.matcher(path).matches();
	}

	private Optional<Long> resolveProjectId(String path) {
		Optional<Long> direct = longGroup(PROJECT, path).or(() -> longGroup(OPERATIONS_PROJECT, path));
		if (direct.isPresent()) {
			return direct;
		}
		return stringLookup(RUN, path, accessService::projectForRun)
			.or(() -> longLookup(GAP, path, accessService::projectForGap))
			.or(() -> stringLookup(EVOLUTION_CANDIDATE, path, accessService::projectForEvolutionCandidate))
			.or(() -> stringLookup(REPLAY_RUN, path, accessService::projectForEvaluationJob))
			.or(() -> stringLookup(OPTIMIZATION_CANDIDATE, path, accessService::projectForOptimizationCandidate))
			.or(() -> stringLookup(JOB, path, accessService::projectForEvaluationJob))
			.or(() -> stringLookup(RELEASE, path, accessService::projectForRelease))
			.or(() -> stringLookup(EPISODE, path, accessService::projectForEpisode))
			.or(() -> stringLookup(ATTEMPT, path, accessService::projectForAttempt))
			.or(() -> stringLookup(TRAJECTORY_PATTERN, path, accessService::projectForTrajectoryPattern));
	}

	private Optional<Long> longGroup(Pattern pattern, String path) {
		Matcher matcher = pattern.matcher(path);
		return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
	}

	private Optional<Long> longLookup(Pattern pattern, String path, Function<Long, Optional<Long>> lookup) {
		return longGroup(pattern, path).flatMap(lookup);
	}

	private Optional<Long> stringLookup(Pattern pattern, String path, Function<String, Optional<Long>> lookup) {
		Matcher matcher = pattern.matcher(path);
		return matcher.matches() ? lookup.apply(matcher.group(1)) : Optional.empty();
	}

	private boolean readOnly(HttpMethod method) {
		return method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
	}

	private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
		byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
		exchange.getResponse().setStatusCode(status);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		exchange.getResponse().getHeaders().setCacheControl("no-store");
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
	}

	private static final class MissingPrincipalException extends SecurityException {

	}

}
