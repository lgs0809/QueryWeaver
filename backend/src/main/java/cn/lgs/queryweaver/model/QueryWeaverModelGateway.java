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
package cn.lgs.queryweaver.model;

import cn.lgs.queryweaver.common.BlockingExecutionGuard;
import cn.lgs.queryweaver.observability.QueryWeaverMetrics;
import cn.lgs.queryweaver.service.llm.LlmInvocationOptions;
import cn.lgs.queryweaver.service.llm.LlmService;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.PrematureCloseException;
import reactor.util.retry.Retry;

/**
 * Single infrastructure boundary for blocking QueryWeaver chat-model calls.
 *
 * <p>All semantic model users inherit the same timeout, retry, concurrency and circuit-breaker
 * semantics. Business services must not implement their own transport retry loops.
 */
@Slf4j
@Component
public class QueryWeaverModelGateway {

	private final LlmService llmService;

	private final QueryWeaverMetrics metrics;

	private final PlannerReasoningProperties reasoningProperties;

	private final Semaphore permits;

	private final Duration permitTimeout;

	private final Duration attemptTimeout;

	private final Duration totalTimeout;

	private final Duration retryDelay;

	private final int maxRetries;

	private final int circuitFailureThreshold;

	private final long circuitOpenMillis;

	private final AtomicInteger consecutiveTransientFailures = new AtomicInteger();

	private final AtomicLong circuitOpenUntilEpochMillis = new AtomicLong();

	@Autowired
	public QueryWeaverModelGateway(LlmService llmService, QueryWeaverMetrics metrics,
			PlannerReasoningProperties reasoningProperties,
			@Value("${queryweaver.model-gateway.max-concurrency:8}") int maxConcurrency,
			@Value("${queryweaver.model-gateway.permit-timeout-ms:1000}") long permitTimeoutMs,
			@Value("${queryweaver.model-gateway.attempt-timeout-ms:60000}") long attemptTimeoutMs,
			@Value("${queryweaver.model-gateway.total-timeout-ms:120000}") long totalTimeoutMs,
			@Value("${queryweaver.model-gateway.retry-delay-ms:300}") long retryDelayMs,
			@Value("${queryweaver.model-gateway.max-retries:1}") int maxRetries,
			@Value("${queryweaver.model-gateway.circuit-failure-threshold:5}") int circuitFailureThreshold,
			@Value("${queryweaver.model-gateway.circuit-open-ms:15000}") long circuitOpenMillis) {
		this.llmService = Objects.requireNonNull(llmService, "llmService");
		this.metrics = metrics == null ? QueryWeaverMetrics.noop() : metrics;
		this.reasoningProperties = reasoningProperties == null ? disabledReasoningProperties() : reasoningProperties;
		this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
		this.permitTimeout = Duration.ofMillis(Math.max(1L, permitTimeoutMs));
		this.attemptTimeout = Duration.ofMillis(Math.max(1L, attemptTimeoutMs));
		this.totalTimeout = Duration.ofMillis(Math.max(attemptTimeout.toMillis(), totalTimeoutMs));
		this.retryDelay = Duration.ofMillis(Math.max(0L, retryDelayMs));
		this.maxRetries = Math.max(0, maxRetries);
		this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
		this.circuitOpenMillis = Math.max(1L, circuitOpenMillis);
	}

	/** Constructor retained for focused tests that need transport-policy controls. */
	public QueryWeaverModelGateway(LlmService llmService, QueryWeaverMetrics metrics, int maxConcurrency,
			long permitTimeoutMs, long attemptTimeoutMs, long totalTimeoutMs, long retryDelayMs, int maxRetries,
			int circuitFailureThreshold, long circuitOpenMillis) {
		this(llmService, metrics, disabledReasoningProperties(), maxConcurrency, permitTimeoutMs, attemptTimeoutMs,
				totalTimeoutMs, retryDelayMs, maxRetries, circuitFailureThreshold, circuitOpenMillis);
	}

	/** Lightweight constructor retained for focused unit tests. */
	public QueryWeaverModelGateway(LlmService llmService) {
		this(llmService, QueryWeaverMetrics.noop(), 8, 1000, 60000, 120000, 300, 1, 5, 15000);
	}

	public ModelCallResult complete(ModelCallPurpose purpose, String systemPrompt, String userPrompt) {
		return complete(purpose, systemPrompt, userPrompt, defaultInvocationOptions(purpose));
	}

	/** Executes a governed call with an explicit per-call profile, used by paired planner ablations. */
	public ModelCallResult complete(ModelCallPurpose purpose, String systemPrompt, String userPrompt,
			LlmInvocationOptions requestedOptions) {
		BlockingExecutionGuard.assertBlockingAllowed("model-gateway.complete");
		ModelCallPurpose effectivePurpose = purpose == null ? ModelCallPurpose.OTHER : purpose;
		LlmInvocationOptions effectiveOptions = requestedOptions == null ? LlmInvocationOptions.none() : requestedOptions;
		assertCircuitClosed();
		boolean acquired = false;
		long started = System.nanoTime();
		String callId = UUID.randomUUID().toString();
		AtomicInteger attempts = new AtomicInteger();
		AtomicLong promptTokens = new AtomicLong();
		AtomicLong completionTokens = new AtomicLong();
		AtomicBoolean optionsApplied = new AtomicBoolean(!effectiveOptions.empty());
		AtomicReference<String> downgradeReason = new AtomicReference<>();
		if (!effectiveOptions.empty() && !llmService.supportsInvocationOptions(effectiveOptions)) {
			optionsApplied.set(false);
			downgradeReason.set("LLM_SERVICE_OPTIONS_UNSUPPORTED");
		}
		try {
			acquired = permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!acquired) {
				throw new ModelCapacityException("Model gateway concurrency limit is saturated");
			}
			String response = Flux.defer(() -> {
					attempts.incrementAndGet();
					Flux<ChatResponse> responses = optionsApplied.get()
							? llmService.call(systemPrompt, userPrompt, effectiveOptions)
							: llmService.call(systemPrompt, userPrompt);
					if (optionsApplied.get() && reasoningProperties.isDowngradeOnUnsupported()) {
						responses = responses.onErrorResume(this::isUnsupportedReasoningFailure, failure -> {
							optionsApplied.set(false);
							downgradeReason.set("PROVIDER_REASONING_OPTIONS_UNSUPPORTED");
							log.warn("Provider rejected reasoning options for callId={}; retrying governed call without them",
									callId);
							return llmService.call(systemPrompt, userPrompt);
						});
					}
					return llmService.toStringFlux(responses
						.doOnNext(chatResponse -> captureUsage(chatResponse, promptTokens, completionTokens)));
				})
				.collect(StringBuilder::new, StringBuilder::append)
				.map(StringBuilder::toString)
				.map(this::requireNonEmptyResponse)
				.timeout(attemptTimeout)
				.retryWhen(Retry.fixedDelay(maxRetries, retryDelay)
					.filter(this::isTransientModelFailure)
					.onRetryExhaustedThrow((spec, signal) -> signal.failure()))
				.block(totalTimeout);
			if (response == null) {
				throw new TransientModelException("Model returned no terminal response");
			}
			consecutiveTransientFailures.set(0);
			long latencyMs = elapsedMillis(started);
			metrics.modelCall(effectivePurpose, true, Math.max(0, attempts.get() - 1), latencyMs, "none",
					promptTokens.get(), completionTokens.get());
			InvocationProfile invocationProfile = new InvocationProfile(effectiveOptions.requestsReasoning(),
					optionsApplied.get() && effectiveOptions.requestsReasoning(), effectiveOptions.reasoningEffort(),
					effectiveOptions.modelOverride(), downgradeReason.get());
			log.debug(
					"QueryWeaver model call succeeded callId={} purpose={} attempts={} latencyMs={} promptTokens={} completionTokens={} reasoningRequested={} reasoningApplied={} downgradeReason={}",
					callId, effectivePurpose, attempts.get(), latencyMs, promptTokens.get(), completionTokens.get(),
					invocationProfile.reasoningRequested(), invocationProfile.reasoningApplied(), downgradeReason.get());
			return new ModelCallResult(callId, effectivePurpose, response, attempts.get(), latencyMs, promptTokens.get(),
					completionTokens.get(), invocationProfile);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ModelCapacityException("Interrupted while waiting for model capacity", ex);
		}
		catch (RuntimeException ex) {
			boolean transientFailure = isTransientModelFailure(ex);
			if (transientFailure && consecutiveTransientFailures.incrementAndGet() >= circuitFailureThreshold) {
				circuitOpenUntilEpochMillis.set(System.currentTimeMillis() + circuitOpenMillis);
			}
			long latencyMs = elapsedMillis(started);
			metrics.modelCall(effectivePurpose, false, Math.max(0, attempts.get() - 1), latencyMs,
					errorTag(ex, transientFailure), promptTokens.get(), completionTokens.get());
			log.warn("QueryWeaver model call failed callId={} purpose={} attempts={} latencyMs={} error={}", callId,
					effectivePurpose, attempts.get(), latencyMs, ex.toString());
			throw ex;
		}
		finally {
			if (acquired) {
				permits.release();
			}
		}
	}

	private LlmInvocationOptions defaultInvocationOptions(ModelCallPurpose purpose) {
		if (purpose != ModelCallPurpose.SEMANTIC_PLANNING || !reasoningProperties.isEnabled()) {
			return LlmInvocationOptions.none();
		}
		return new LlmInvocationOptions(reasoningProperties.getModelOverride(), reasoningProperties.getEffort());
	}

	private boolean isUnsupportedReasoningFailure(Throwable failure) {
		for (Throwable current = failure; current != null && current.getCause() != current; current = current.getCause()) {
			String message = Objects.toString(current.getMessage(), "").toLowerCase(Locale.ROOT);
			if (current instanceof WebClientResponseException responseException) {
				message = message + " " + Objects.toString(responseException.getResponseBodyAsString(), "")
					.toLowerCase(Locale.ROOT);
				int status = responseException.getStatusCode().value();
				if (status != 400 && status != 422) {
					continue;
				}
			}
			boolean namesReasoning = message.contains("reasoning_effort") || message.contains("reasoning effort");
			boolean rejectsParameter = message.contains("unsupported") || message.contains("not supported")
					|| message.contains("unknown") || message.contains("unrecognized") || message.contains("invalid");
			if (namesReasoning && rejectsParameter) {
				return true;
			}
		}
		return false;
	}

	private static PlannerReasoningProperties disabledReasoningProperties() {
		PlannerReasoningProperties properties = new PlannerReasoningProperties();
		properties.setEnabled(false);
		properties.setDowngradeOnUnsupported(true);
		return properties;
	}

	private void assertCircuitClosed() {
		long until = circuitOpenUntilEpochMillis.get();
		if (until <= 0L) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now >= until && circuitOpenUntilEpochMillis.compareAndSet(until, 0L)) {
			consecutiveTransientFailures.set(0);
			return;
		}
		if (now < until) {
			throw new ModelCircuitOpenException("Model gateway circuit is open until " + until);
		}
	}

	private void captureUsage(ChatResponse response, AtomicLong promptTokens, AtomicLong completionTokens) {
		if (response == null || response.getMetadata() == null) {
			return;
		}
		Usage usage = response.getMetadata().getUsage();
		if (usage == null) {
			return;
		}
		promptTokens.accumulateAndGet(Math.max(0L, usage.getPromptTokens()), Math::max);
		completionTokens.accumulateAndGet(Math.max(0L, usage.getCompletionTokens()), Math::max);
	}

	private String requireNonEmptyResponse(String response) {
		if (response == null || response.isBlank()) {
			throw new TransientModelException("Model returned an empty response");
		}
		return response;
	}

	private boolean isTransientModelFailure(Throwable failure) {
		for (Throwable current = failure; current != null && current.getCause() != current; current = current.getCause()) {
			if (current instanceof TransientModelException || current instanceof PrematureCloseException
					|| current instanceof WebClientRequestException || current instanceof TimeoutException) {
				return true;
			}
			if (current instanceof WebClientResponseException responseException) {
				HttpStatusCode status = responseException.getStatusCode();
				return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
			}
		}
		return false;
	}

	private String errorTag(Throwable ex, boolean transientFailure) {
		if (ex instanceof ModelCapacityException) {
			return "capacity";
		}
		if (ex instanceof ModelCircuitOpenException) {
			return "circuit_open";
		}
		return transientFailure ? "transient" : "deterministic";
	}

	private long elapsedMillis(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
	}

	public record ModelCallResult(String callId, ModelCallPurpose purpose, String response, int attempts,
			long latencyMs, long promptTokens, long completionTokens, InvocationProfile invocationProfile) {
		public ModelCallResult {
			invocationProfile = invocationProfile == null ? InvocationProfile.baseline() : invocationProfile;
		}

		public ModelCallResult(String callId, ModelCallPurpose purpose, String response, int attempts, long latencyMs,
				long promptTokens, long completionTokens) {
			this(callId, purpose, response, attempts, latencyMs, promptTokens, completionTokens,
					InvocationProfile.baseline());
		}

		public ModelCallResult(String callId, ModelCallPurpose purpose, String response, int attempts, long latencyMs) {
			this(callId, purpose, response, attempts, latencyMs, 0L, 0L, InvocationProfile.baseline());
		}
	}

	public record InvocationProfile(boolean reasoningRequested, boolean reasoningApplied, String reasoningEffort,
			String modelOverride, String downgradeReason) {
		public static InvocationProfile baseline() {
			return new InvocationProfile(false, false, null, null, null);
		}
	}

	private static final class TransientModelException extends IllegalStateException {
		private TransientModelException(String message) {
			super(message);
		}
	}

	public static class ModelCapacityException extends IllegalStateException {
		public ModelCapacityException(String message) {
			super(message);
		}

		public ModelCapacityException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static class ModelCircuitOpenException extends IllegalStateException {
		public ModelCircuitOpenException(String message) {
			super(message);
		}
	}
}
