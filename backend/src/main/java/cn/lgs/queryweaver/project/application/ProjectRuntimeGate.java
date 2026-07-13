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
package cn.lgs.queryweaver.project.application;

import cn.lgs.queryweaver.project.domain.InitializationAnalysisStatus;
import cn.lgs.queryweaver.project.domain.ProjectNotReadyException;
import cn.lgs.queryweaver.project.domain.ProjectRuntimeContext;
import cn.lgs.queryweaver.project.domain.ProjectStatus;
import cn.lgs.queryweaver.project.domain.ProjectVersionStatus;
import cn.lgs.queryweaver.project.domain.SemanticGap;
import cn.lgs.queryweaver.project.domain.SemanticProject;
import cn.lgs.queryweaver.project.domain.SemanticProjectRepository;
import cn.lgs.queryweaver.project.domain.SemanticProjectVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectRuntimeGate {

	private final SemanticProjectRepository repository;

	public ProjectRuntimeContext requireReadyByProject(Long projectId) {
		SemanticProject project = repository.findProject(projectId)
			.orElseThrow(() -> new ProjectNotReadyException("Semantic project does not exist", projectId, null));
		if (project.getActiveVersionId() == null) {
			throw new ProjectNotReadyException("Project has no active published version", projectId, null);
		}
		SemanticProjectVersion version = repository.findVersion(project.getActiveVersionId())
			.orElseThrow(() -> new ProjectNotReadyException("Active semantic project version does not exist", projectId,
					null));
		return validate(project, version, "Project active version is not PUBLISHED");
	}

	private ProjectRuntimeContext validate(SemanticProject project, SemanticProjectVersion version,
			String inactiveMessage) {
		SemanticGap nextGap = repository.findNextOpenGap(project.getId(), version.getId()).orElse(null);
		if (project.getStatus() != ProjectStatus.READY) {
			throw notReady("Semantic project is not READY", project, nextGap);
		}
		if (version.getStatus() != ProjectVersionStatus.PUBLISHED
				|| !version.getId().equals(project.getActiveVersionId())) {
			throw notReady(inactiveMessage, project, nextGap);
		}
		if (version.getAnalysisStatus() != InitializationAnalysisStatus.COMPLETED) {
			throw notReady("Semantic project analysis is not completed", project, nextGap);
		}
		if (nextGap != null || repository.countOpenGaps(project.getId(), version.getId()) > 0) {
			throw notReady("Semantic project initialization is incomplete. Resume from the next gap.", project,
					nextGap);
		}
		if (version.getCatalogHash() == null || version.getCatalogHash().isBlank()) {
			throw notReady("Published semantic version has no validated catalog hash", project, nextGap);
		}
		return new ProjectRuntimeContext(project.getId(), version.getId(), version.getCatalogHash());
	}

	private ProjectNotReadyException notReady(String message, SemanticProject project, SemanticGap nextGap) {
		return new ProjectNotReadyException(message, project.getId(), nextGap == null ? null : nextGap.getId());
	}

}
