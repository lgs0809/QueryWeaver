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
package cn.lgs.queryweaver.common;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Server-resolved identity, role and request envelope for governed mutations. */
public record OperatorContext(String operator, OperatorRole role, String source, String requestId,
		String idempotencyKey) {

	public OperatorContext(String operator, String source, String requestId, String idempotencyKey) {
		this(operator, OperatorRole.VIEWER, source, requestId, idempotencyKey);
	}

	public OperatorContext {
		if (!StringUtils.hasText(operator) || role == null || !StringUtils.hasText(source)
				|| !StringUtils.hasText(requestId) || !StringUtils.hasText(idempotencyKey)) {
			throw new IllegalArgumentException(
					"Operator identity, role, source, requestId and idempotencyKey are required");
		}
	}

	public static OperatorContext system(String operation) {
		String requestId = UUID.randomUUID().toString();
		return new OperatorContext("queryweaver-system", OperatorRole.ADMIN, "SYSTEM", requestId,
				operation + ":" + requestId);
	}

	@Component
	public static class Resolver {

		private final OperatorContextProperties properties;

		public Resolver() {
			this(new OperatorContextProperties());
		}

		@Autowired
		public Resolver(OperatorContextProperties properties) {
			this.properties = properties;
		}

		public OperatorContext resolve(HttpHeaders headers, Principal principal, String operation) {
			String requestId = header(headers, "X-Request-ID", UUID.randomUUID().toString());
			String idempotencyKey = header(headers, "Idempotency-Key", operation + ":" + requestId);
			if (principal != null && StringUtils.hasText(principal.getName())) {
				String operator = principal.getName().trim();
				OperatorRole role = authenticatedRole(principal);
				if (role == null) {
					role = mappedRole(operator);
				}
				if (role == null) {
					if (!properties.isDevelopmentMode()) {
						throw new SecurityException(
								"Authenticated operator has no server-side role mapping: " + operator);
					}
					role = properties.getDefaultRole();
				}
				return new OperatorContext(operator, role, "AUTHENTICATED_HTTP", requestId, idempotencyKey);
			}
			if (!properties.isDevelopmentMode()) {
				throw new SecurityException("Authenticated operator is required outside development mode");
			}
			String operator = required(properties.getDefaultOperator(), "queryweaver.operator.default-operator");
			OperatorRole role = Objects.requireNonNull(properties.getDefaultRole(),
					"queryweaver.operator.default-role is required");
			return new OperatorContext(operator, role, "DEVELOPMENT_SINGLE_USER", requestId, idempotencyKey);
		}

		private OperatorRole mappedRole(String operator) {
			Map<String, OperatorRole> mappings = properties.getRoleMappings();
			return mappings == null ? null : mappings.get(operator);
		}

		private OperatorRole authenticatedRole(Principal principal) {
			if (!(principal instanceof Authentication authentication)) {
				return null;
			}
			OperatorRole resolved = null;
			for (GrantedAuthority authority : authentication.getAuthorities()) {
				String value = authority.getAuthority();
				if (!StringUtils.hasText(value)) {
					continue;
				}
				String normalized = value.trim().toUpperCase();
				if (normalized.startsWith("ROLE_")) {
					normalized = normalized.substring("ROLE_".length());
				}
				if (normalized.startsWith("QUERYWEAVER_")) {
					normalized = normalized.substring("QUERYWEAVER_".length());
				}
				try {
					OperatorRole candidate = OperatorRole.valueOf(normalized);
					if (resolved == null || candidate.atLeast(resolved)) {
						resolved = candidate;
					}
				}
				catch (IllegalArgumentException ignored) {
					// Ignore unrelated authorities.
				}
			}
			return resolved;
		}

		private String header(HttpHeaders headers, String name, String fallback) {
			String value = headers == null ? null : headers.getFirst(name);
			return StringUtils.hasText(value) ? value.trim() : Objects.requireNonNull(fallback);
		}

		private String required(String value, String field) {
			if (!StringUtils.hasText(value)) {
				throw new IllegalStateException(field + " is required");
			}
			return value.trim();
		}

	}

}
