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
package cn.lgs.semevosql.clarification;

public class RuntimeClarificationRequiredException extends IllegalStateException {

	private final String runId;

	private final String clarificationId;

	public RuntimeClarificationRequiredException(String runId, String clarificationId) {
		super("Runtime clarification is required before SQL generation; runId=" + runId + "; clarificationId="
				+ clarificationId);
		this.runId = runId;
		this.clarificationId = clarificationId;
	}

	public String getRunId() {
		return runId;
	}

	public String getClarificationId() {
		return clarificationId;
	}

}
