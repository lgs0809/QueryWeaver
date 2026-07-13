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
package cn.lgs.queryweaver.semantic.application;

import cn.lgs.queryweaver.project.domain.ProjectVersionStatus;
import cn.lgs.queryweaver.project.domain.SemanticProjectRepository;
import cn.lgs.queryweaver.project.domain.SemanticProjectVersion;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.AttemptView;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.DeletedMaterial;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.IngestionResult;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.MaterialRegistration;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.MaterialView;
import cn.lgs.queryweaver.semantic.application.SemanticMaterialIngestionService.ProvenanceView;
import cn.lgs.queryweaver.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.queryweaver.semantic.domain.MaterialCategory;
import cn.lgs.queryweaver.semantic.domain.MaterialLifecycle;
import cn.lgs.queryweaver.semantic.domain.ProjectDocumentType;
import cn.lgs.queryweaver.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticMaterial;
import cn.lgs.queryweaver.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.queryweaver.semantic.domain.SemanticMaterialRepository;
import cn.lgs.queryweaver.semantic.domain.SemanticMaterialSourceType;
import cn.lgs.queryweaver.semantic.domain.SemanticMaterialType;
import cn.lgs.queryweaver.service.file.ByteArrayMultipartFile;
import cn.lgs.queryweaver.service.file.FileStorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** File lifecycle and version cloning facade for Project documents. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDocumentService {

	private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

	private static final int MAX_EXTRACTED_CHARACTERS = 5_000_000;

	private static final int MAX_ARCHIVE_ENTRIES = 500;

	private static final long MAX_ARCHIVE_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;

	private static final Pattern MIGRATION_SQL = Pattern.compile("(?i)^V\\d+(?:_\\d+)*__.+\\.sql$");

	private final SemanticMaterialIngestionService ingestionService;

	private final SemanticMaterialRepository materialRepository;

	private final ProjectEvidenceRepository evidenceRepository;

	private final BusinessQueryScenarioRepository scenarioRepository;

	private final SemanticProjectRepository projectRepository;

	private final FileStorageService fileStorageService;

	private final StructuredSemanticMaterialParser structuredParser;

	public Mono<IngestionResult> upload(Long projectId, Long versionId, ProjectDocumentType documentType,
			SemanticMaterialType materialType, Integer datasourceId, String sourceName, String sourceLocation,
			FilePart file) {
		return upload(projectId, versionId, documentType, null, null, materialType, datasourceId, sourceName,
				sourceLocation, file);
	}

	public Mono<IngestionResult> upload(Long projectId, Long versionId, ProjectDocumentType documentType,
			MaterialCategory materialCategory, MaterialLifecycle lifecycle, SemanticMaterialType materialType,
			Integer datasourceId, String sourceName, String sourceLocation, FilePart file) {
		if (file == null || file.filename() == null || file.filename().isBlank()) {
			return Mono.error(new IllegalArgumentException("Project document file is required"));
		}
		String originalFilename = safeFilename(file.filename());
		String subPath = documentSubPath(projectId, versionId);
		return fileStorageService.storeFile(file, subPath).flatMap(filePath -> Mono.fromCallable(() -> {
			try {
				Resource resource = fileStorageService.getFileResource(filePath);
				long fileSize = resource.contentLength();
				if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
					throw new IllegalArgumentException("Project document size must be between 1 byte and 20 MB");
				}
				String content = extractText(resource);
				SemanticMaterialType resolvedType = materialType == null
						? inferMaterialType(originalFilename, documentType, materialCategory) : materialType;
				MaterialRegistration registration = new MaterialRegistration(documentType, materialCategory, lifecycle,
						resolvedType, SemanticMaterialSourceType.UPLOAD, null,
						sourceName == null || sourceName.isBlank() ? originalFilename : sourceName, originalFilename,
						file.headers().getContentType() == null ? null : file.headers().getContentType().toString(),
						filePath, fileSize, sourceLocation == null || sourceLocation.isBlank()
								? "file:" + originalFilename : sourceLocation,
						datasourceId, content, extractionModel(resolvedType, content));
				IngestionResult result = ingestionService.ingestDocument(projectId, versionId, registration);
				if (result.duplicate()) {
					fileStorageService.deleteFile(filePath);
				}
				return result;
			}
			catch (RuntimeException | IOException ex) {
				fileStorageService.deleteFile(filePath);
				throw ex;
			}
		}).subscribeOn(Schedulers.boundedElastic()));
	}

	public Mono<BundleIngestionResult> uploadBundle(Long projectId, Long versionId, MaterialCategory defaultCategory,
			MaterialLifecycle lifecycle, Integer datasourceId, String sourceName, String sourceLocation,
			FilePart file) {
		if (file == null || file.filename() == null || file.filename().isBlank()) {
			return Mono.error(new IllegalArgumentException("Project bundle file is required"));
		}
		String originalFilename = safeFilename(file.filename());
		if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			return Mono.error(new IllegalArgumentException("Project bundle must be a ZIP archive"));
		}
		String subPath = documentSubPath(projectId, versionId);
		return fileStorageService.storeFile(file, subPath).flatMap(filePath -> Mono.fromCallable(() -> {
			try {
				Resource resource = fileStorageService.getFileResource(filePath);
				long fileSize = resource.contentLength();
				if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
					throw new IllegalArgumentException("Project bundle size must be between 1 byte and 20 MB");
				}
				return ingestArchive(projectId, versionId, originalFilename,
						defaultCategory == null ? MaterialCategory.OTHER : defaultCategory,
						lifecycle == null ? MaterialLifecycle.CURRENT : lifecycle, datasourceId, sourceName,
						sourceLocation, resource);
			}
			catch (RuntimeException | IOException ex) {
				throw ex;
			}
			finally {
				fileStorageService.deleteFile(filePath);
			}
		}).subscribeOn(Schedulers.boundedElastic()));
	}

	BundleIngestionResult ingestArchive(Long projectId, Long versionId, String archiveName,
			MaterialCategory defaultCategory, MaterialLifecycle lifecycle, Integer datasourceId, String sourceName,
			String sourceLocation, Resource resource) throws IOException {
		requireMutableDraft(projectId, versionId);
		List<BundleEntryResult> entries = new ArrayList<>();
		long totalBytes = 0;
		int entryCount = 0;
		try (ZipInputStream zip = new ZipInputStream(resource.getInputStream(), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entryCount++;
				if (entryCount > MAX_ARCHIVE_ENTRIES) {
					throw new IllegalArgumentException("Project bundle contains more than 500 entries");
				}
				String entryName = validateArchiveEntry(entry.getName());
				if (entry.isDirectory() || ignoredArchiveEntry(entryName) || !supportedArchiveEntry(entryName)) {
					zip.closeEntry();
					continue;
				}
				byte[] bytes = readArchiveEntry(zip);
				totalBytes += bytes.length;
				if (totalBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
					throw new IllegalArgumentException("Project bundle expands beyond 100 MB");
				}
				MaterialCategory category = inferArchiveCategory(entryName, defaultCategory);
				ProjectDocumentType entryDocumentType = documentTypeForCategory(category);
				SemanticMaterialType entryMaterialType = inferArchiveMaterialType(entryName, category);
				String content = extractText(namedResource(bytes, safeFilename(entryName)));
				String entrySource = "archive:" + archiveName + "!/" + entryName;
				if (sourceLocation != null && !sourceLocation.isBlank()) {
					entrySource = sourceLocation.trim() + " -> " + entrySource;
				}
				String entrySourceName = sourceName == null || sourceName.isBlank() ? entryName
						: sourceName.trim() + "/" + entryName;
				MaterialRegistration registration = new MaterialRegistration(entryDocumentType, category, lifecycle,
						entryMaterialType, SemanticMaterialSourceType.UPLOAD, null, entrySourceName,
						safeFilename(entryName), "application/octet-stream", null, (long) bytes.length, entrySource,
						datasourceId, content, extractionModel(entryMaterialType, content));
				IngestionResult result = ingestionService.ingestDocument(projectId, versionId, registration);
				entries.add(new BundleEntryResult(entryName, category,
						result.material() == null ? null : result.material().id(), result.status(),
						result.duplicate()));
				zip.closeEntry();
			}
		}
		if (entries.isEmpty()) {
			throw new IllegalArgumentException("Project bundle contains no supported project materials");
		}
		int duplicateCount = (int) entries.stream().filter(BundleEntryResult::duplicate).count();
		return new BundleIngestionResult(archiveName, entries.size(), duplicateCount, entries.size() - duplicateCount,
				List.copyOf(entries));
	}

	private byte[] readArchiveEntry(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long size = 0;
		int read;
		while ((read = zip.read(buffer)) >= 0) {
			if (read == 0) {
				continue;
			}
			size += read;
			if (size > MAX_FILE_SIZE) {
				throw new IllegalArgumentException("A project bundle entry exceeds 20 MB");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private String validateArchiveEntry(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Project bundle contains an unnamed entry");
		}
		String normalizedSeparators = name.replace('\\', '/');
		if (normalizedSeparators.startsWith("/") || normalizedSeparators.matches("^[A-Za-z]:/.*")) {
			throw new IllegalArgumentException("Project bundle contains an absolute entry path: " + name);
		}
		Path normalized = Path.of(normalizedSeparators).normalize();
		String value = normalized.toString().replace('\\', '/');
		if (value.equals("..") || value.startsWith("../")) {
			throw new IllegalArgumentException("Project bundle contains a path traversal entry: " + name);
		}
		return value;
	}

	private boolean ignoredArchiveEntry(String entryName) {
		String path = "/" + entryName.toLowerCase(Locale.ROOT) + "/";
		return path.contains("/.git/") || path.contains("/node_modules/") || path.contains("/target/")
				|| path.contains("/build/") || path.contains("/dist/") || path.contains("/.idea/")
				|| path.contains("/.gradle/") || path.contains("/__macosx/");
	}

	private boolean supportedArchiveEntry(String entryName) {
		String lower = entryName.toLowerCase(Locale.ROOT);
		return List
			.of(".java", ".kt", ".py", ".go", ".ts", ".tsx", ".js", ".xml", ".sql", ".md", ".markdown", ".txt", ".json",
					".yaml", ".yml", ".properties", ".csv", ".pdf", ".doc", ".docx", ".xls", ".xlsx")
			.stream()
			.anyMatch(lower::endsWith);
	}

	private MaterialCategory inferArchiveCategory(String entryName, MaterialCategory fallback) {
		String lower = entryName.toLowerCase(Locale.ROOT);
		String filename = safeFilename(entryName);
		if (lower.contains("/src/test/") || lower.contains("/tests/") || lower.contains("/test/")
				|| filename.toLowerCase(Locale.ROOT).matches(".*(?:test|tests|spec)\\.(?:java|kt|py|ts|tsx|js)$")) {
			return MaterialCategory.TEST_MATERIAL;
		}
		if (lower.contains("/db/migration/") || lower.contains("/migration/")
				|| MIGRATION_SQL.matcher(filename).matches()) {
			return MaterialCategory.DATABASE_MIGRATION;
		}
		if (lower.contains("mapper") || lower.contains("repository") || lower.contains("/dao/")
				|| lower.endsWith("mapper.xml")) {
			return MaterialCategory.DATA_ACCESS_CODE;
		}
		if (lower.contains("openapi") || lower.contains("swagger") || lower.contains("/api-doc")) {
			return MaterialCategory.API_DOCUMENTATION;
		}
		if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py") || lower.endsWith(".go")
				|| lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js")) {
			return MaterialCategory.BACKEND_SOURCE;
		}
		if (lower.endsWith(".sql")) {
			return fallback == MaterialCategory.DATABASE_SCHEMA ? MaterialCategory.DATABASE_SCHEMA
					: MaterialCategory.SQL_QUERY;
		}
		return fallback;
	}

	private ProjectDocumentType documentTypeForCategory(MaterialCategory category) {
		return switch (category) {
			case DATABASE_SCHEMA, DATA_DICTIONARY -> ProjectDocumentType.DATA_DICTIONARY;
			case METRIC_DEFINITION -> ProjectDocumentType.METRIC_SPEC;
			case BUSINESS_GLOSSARY -> ProjectDocumentType.GLOSSARY;
			case REPORT_OR_BI -> ProjectDocumentType.REPORT_SPEC;
			case SQL_QUERY -> ProjectDocumentType.HISTORICAL_SQL;
			case SYSTEM_DESIGN -> ProjectDocumentType.SYSTEM_RESPONSIBILITY;
			case BUSINESS_RULE -> ProjectDocumentType.SYNC_POLICY;
			default -> ProjectDocumentType.REQUIREMENT;
		};
	}

	private SemanticMaterialType inferArchiveMaterialType(String entryName, MaterialCategory category) {
		String lower = entryName.toLowerCase(Locale.ROOT);
		if (category == MaterialCategory.SQL_QUERY) {
			return SemanticMaterialType.HISTORICAL_SQL;
		}
		if ((category == MaterialCategory.DATABASE_SCHEMA || category == MaterialCategory.DATABASE_MIGRATION)
				&& lower.endsWith(".sql")) {
			return SemanticMaterialType.DDL;
		}
		if (category == MaterialCategory.DATA_DICTIONARY && lower.endsWith(".json")) {
			return SemanticMaterialType.JSON;
		}
		if (category == MaterialCategory.DATA_DICTIONARY && (lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
			return SemanticMaterialType.YAML;
		}
		return SemanticMaterialType.MARKDOWN;
	}

	private Resource namedResource(byte[] bytes, String filename) {
		return new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
	}

	public IngestionResult ingestInline(Long projectId, Long versionId, ProjectDocumentType documentType,
			SemanticMaterialType materialType, Integer datasourceId, String sourceName, String sourceLocation,
			String content) {
		return ingestInline(projectId, versionId, documentType, null, null, materialType, datasourceId, sourceName,
				sourceLocation, content);
	}

	public IngestionResult ingestInline(Long projectId, Long versionId, ProjectDocumentType documentType,
			MaterialCategory materialCategory, MaterialLifecycle lifecycle, SemanticMaterialType materialType,
			Integer datasourceId, String sourceName, String sourceLocation, String content) {
		MaterialRegistration registration = new MaterialRegistration(documentType, materialCategory, lifecycle,
				materialType, SemanticMaterialSourceType.INLINE, null, sourceName, null, "text/plain", null,
				content == null ? null : (long) content.getBytes(StandardCharsets.UTF_8).length, sourceLocation,
				datasourceId, content, extractionModel(materialType, content));
		return ingestionService.ingestDocument(projectId, versionId, registration);
	}

	public List<MaterialView> list(Long projectId, Long versionId) {
		return ingestionService.list(projectId, versionId);
	}

	public MaterialView get(Long projectId, Long versionId, Long documentId) {
		return ingestionService.get(projectId, versionId, documentId);
	}

	public DocumentContent content(Long projectId, Long versionId, Long documentId) {
		requireVersion(projectId, versionId);
		SemanticMaterial material = materialRepository.findById(documentId)
			.orElseThrow(() -> new IllegalArgumentException("Project document not found: " + documentId));
		if (!projectId.equals(material.getProjectId()) || !versionId.equals(material.getProjectVersionId())) {
			throw new IllegalArgumentException("Project document does not belong to the specified project version");
		}
		if (material.getFilePath() == null || material.getFilePath().isBlank()) {
			throw new IllegalStateException("Project document has no downloadable file");
		}
		Resource resource = fileStorageService.getFileResource(material.getFilePath());
		try {
			return new DocumentContent(resource,
					material.getOriginalFilename() == null ? "document" : safeFilename(material.getOriginalFilename()),
					material.getMediaType(), resource.contentLength());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to read project document file", ex);
		}
	}

	public List<AttemptView> attempts(Long projectId, Long versionId, Long documentId) {
		return ingestionService.attempts(projectId, versionId, documentId);
	}

	public List<ProvenanceView> provenance(Long projectId, Long versionId, Long documentId) {
		return ingestionService.provenance(projectId, versionId, documentId);
	}

	public IngestionResult reparse(Long projectId, Long versionId, Long documentId, String extractionModel) {
		return ingestionService.reparse(projectId, versionId, documentId, extractionModel);
	}

	@Transactional
	public DeleteResult delete(Long projectId, Long versionId, Long documentId) {
		DeletedMaterial deleted = ingestionService.delete(projectId, versionId, documentId);
		boolean fileDeleted = deleted.filePath() == null || fileStorageService.deleteFile(deleted.filePath());
		if (!fileDeleted) {
			throw new IllegalStateException("Project document file cleanup failed: " + deleted.filePath());
		}
		return new DeleteResult(documentId, true);
	}

	@Transactional
	public void cloneDocuments(Long projectId, Long sourceVersionId, Long targetVersionId) {
		requireVersion(projectId, sourceVersionId);
		requireMutableDraft(projectId, targetVersionId);
		List<String> copiedFilePaths = new ArrayList<>();
		try {
			for (SemanticMaterial source : materialRepository.findByVersionWithContent(sourceVersionId)) {
				String targetFilePath = copyFile(source, projectId, targetVersionId);
				if (targetFilePath != null) {
					copiedFilePaths.add(targetFilePath);
				}
				SemanticMaterial clone = cloneMaterial(source, projectId, targetVersionId, targetFilePath);
				materialRepository.insert(clone);
				cloneAttempts(source.getId(), clone.getId(), projectId, targetVersionId);
			}
			registerRollbackCleanup(copiedFilePaths);
		}
		catch (RuntimeException ex) {
			copiedFilePaths.forEach(fileStorageService::deleteFile);
			throw ex;
		}
	}

	private void registerRollbackCleanup(List<String> copiedFilePaths) {
		if (copiedFilePaths.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		List<String> rollbackPaths = List.copyOf(copiedFilePaths);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != TransactionSynchronization.STATUS_COMMITTED) {
					rollbackPaths.forEach(fileStorageService::deleteFile);
				}
			}
		});
	}

	private SemanticMaterial cloneMaterial(SemanticMaterial source, Long projectId, Long targetVersionId,
			String targetFilePath) {
		LocalDateTime now = LocalDateTime.now();
		return SemanticMaterial.builder()
			.projectId(projectId)
			.projectVersionId(targetVersionId)
			.documentType(source.getDocumentType())
			.materialCategory(source.getMaterialCategory())
			.lifecycle(source.getLifecycle())
			.materialType(source.getMaterialType())
			.sourceType(source.getSourceType() == SemanticMaterialSourceType.DATABASE_SCAN
					? SemanticMaterialSourceType.DATABASE_SCAN : SemanticMaterialSourceType.CLONE)
			.sourceMaterialId(source.getId())
			.sourceName(source.getSourceName())
			.originalFilename(source.getOriginalFilename())
			.mediaType(source.getMediaType())
			.filePath(targetFilePath)
			.fileSize(source.getFileSize())
			.sourceLocation(source.getSourceLocation())
			.datasourceId(source.getDatasourceId())
			.contentHash(source.getContentHash())
			.content(source.getContent())
			.status(source.getStatus())
			.parseSummary(source.getParseSummary())
			.errorMessage(source.getErrorMessage())
			.createTime(now)
			.updateTime(now)
			.build();
	}

	private void cloneAttempts(Long sourceMaterialId, Long targetMaterialId, Long projectId, Long targetVersionId) {
		List<SemanticMaterialAttempt> attempts = materialRepository.findAttempts(sourceMaterialId);
		if (attempts.isEmpty()) {
			SemanticMaterial source = materialRepository.findById(sourceMaterialId)
				.orElseThrow(() -> new IllegalArgumentException("Semantic material not found: " + sourceMaterialId));
			LocalDateTime now = LocalDateTime.now();
			materialRepository.insertAttempt(SemanticMaterialAttempt.builder()
				.materialId(targetMaterialId)
				.attemptNo(1)
				.status(source.getStatus())
				.contentHash(source.getContentHash())
				.sourceLocation(source.getSourceLocation())
				.extractionModel("clone")
				.parseSummary(source.getParseSummary())
				.errorMessage(source.getErrorMessage())
				.startTime(now)
				.finishTime(now)
				.createTime(now)
				.build());
			return;
		}
		for (SemanticMaterialAttempt sourceAttempt : attempts) {
			SemanticMaterialAttempt targetAttempt = SemanticMaterialAttempt.builder()
				.materialId(targetMaterialId)
				.attemptNo(sourceAttempt.getAttemptNo())
				.status(sourceAttempt.getStatus())
				.contentHash(sourceAttempt.getContentHash())
				.sourceLocation(sourceAttempt.getSourceLocation())
				.extractionModel(sourceAttempt.getExtractionModel())
				.parseSummary(sourceAttempt.getParseSummary())
				.errorMessage(sourceAttempt.getErrorMessage())
				.startTime(sourceAttempt.getStartTime())
				.finishTime(sourceAttempt.getFinishTime())
				.createTime(sourceAttempt.getCreateTime())
				.build();
			materialRepository.insertAttempt(targetAttempt);
			materialRepository.cloneProvenance(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
					projectId, targetVersionId);
			evidenceRepository.cloneAttemptEvidence(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
					projectId, targetVersionId);
			scenarioRepository.cloneAttemptScenarios(sourceAttempt.getId(), targetAttempt.getId(), targetMaterialId,
					projectId, targetVersionId);
		}
	}

	private String copyFile(SemanticMaterial source, Long projectId, Long targetVersionId) {
		if (source.getFilePath() == null || source.getFilePath().isBlank()) {
			return null;
		}
		try {
			Resource resource = fileStorageService.getFileResource(source.getFilePath());
			byte[] bytes = resource.getInputStream().readAllBytes();
			ByteArrayMultipartFile file = new ByteArrayMultipartFile(bytes,
					source.getOriginalFilename() == null ? "document" : source.getOriginalFilename(),
					source.getMediaType());
			return fileStorageService.storeFile(file, documentSubPath(projectId, targetVersionId));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to clone project document file", ex);
		}
	}

	private String extractText(Resource resource) {
		List<Document> documents = new TikaDocumentReader(resource).read();
		StringBuilder content = new StringBuilder();
		for (Document document : documents) {
			if (document.getText() == null || document.getText().isBlank()) {
				continue;
			}
			if (!content.isEmpty()) {
				content.append("\n\n");
			}
			content.append(document.getText());
			if (content.length() > MAX_EXTRACTED_CHARACTERS) {
				throw new IllegalArgumentException("Extracted project document text exceeds 5,000,000 characters");
			}
		}
		if (content.isEmpty()) {
			throw new IllegalArgumentException("No text could be extracted from the project document");
		}
		return content.toString();
	}

	private String extractionModel(SemanticMaterialType materialType, String content) {
		return materialType == SemanticMaterialType.MARKDOWN && !structuredParser.hasEmbeddedCatalogBlock(content)
				? "llm-semantic-extractor" : "built-in-parser";
	}

	private SemanticMaterialType inferMaterialType(String filename, ProjectDocumentType documentType,
			MaterialCategory materialCategory) {
		String lower = filename.toLowerCase(Locale.ROOT);
		if (documentType == ProjectDocumentType.HISTORICAL_SQL || materialCategory == MaterialCategory.SQL_QUERY) {
			return SemanticMaterialType.HISTORICAL_SQL;
		}
		if (lower.endsWith(".sql") && (documentType == ProjectDocumentType.DATA_DICTIONARY
				|| materialCategory == MaterialCategory.DATABASE_SCHEMA
				|| materialCategory == MaterialCategory.DATABASE_MIGRATION)) {
			return SemanticMaterialType.DDL;
		}
		boolean structuredDictionary = materialCategory == null ? documentType == ProjectDocumentType.DATA_DICTIONARY
				: materialCategory == MaterialCategory.DATA_DICTIONARY
						|| materialCategory == MaterialCategory.DATABASE_SCHEMA;
		if (structuredDictionary && lower.endsWith(".json")) {
			return SemanticMaterialType.JSON;
		}
		if (structuredDictionary && (lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
			return SemanticMaterialType.YAML;
		}
		return SemanticMaterialType.MARKDOWN;
	}

	private String documentSubPath(Long projectId, Long versionId) {
		return "queryweaver/projects/" + projectId + "/versions/" + versionId + "/documents";
	}

	private String safeFilename(String filename) {
		String normalized = filename.replace('\\', '/');
		String safe = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").trim();
		if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
			throw new IllegalArgumentException("Invalid project document filename");
		}
		return safe.length() <= 255 ? safe : safe.substring(0, 255);
	}

	private SemanticProjectVersion requireMutableDraft(Long projectId, Long versionId) {
		SemanticProjectVersion version = requireVersion(projectId, versionId);
		if (version.getStatus() != ProjectVersionStatus.DRAFT) {
			throw new IllegalStateException("Project documents can only be changed in a DRAFT version");
		}
		return version;
	}

	private SemanticProjectVersion requireVersion(Long projectId, Long versionId) {
		if (projectRepository.findProject(projectId).isEmpty()) {
			throw new IllegalArgumentException("Semantic project not found: " + projectId);
		}
		SemanticProjectVersion version = projectRepository.findVersion(versionId)
			.orElseThrow(() -> new IllegalArgumentException("Semantic project version not found: " + versionId));
		if (!projectId.equals(version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectId);
		}
		return version;
	}

	public record DeleteResult(Long documentId, boolean fileDeleted) {
	}

	public record DocumentContent(Resource resource, String filename, String mediaType, long contentLength) {
	}

	public record BundleIngestionResult(String archiveName, int processedCount, int duplicateCount, int createdCount,
			List<BundleEntryResult> entries) {
	}

	public record BundleEntryResult(String entryName, MaterialCategory materialCategory, Long materialId,
			cn.lgs.queryweaver.semantic.domain.SemanticMaterialStatus status,
			boolean duplicate) {
	}

}
