package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.*;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/documents")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService service;

    @GetMapping
    public KnowledgeDocumentPageResponse list(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "50")
            @Min(1)
            @Max(100) int size,
            @AuthenticationPrincipal SafeAiUserPrincipal user) {
        return service.list(knowledgeBaseId, user, page, size);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocumentResponse upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false)
            @Size(max = 255) String name,
            @AuthenticationPrincipal SafeAiUserPrincipal user) {
        return service.uploadNew(knowledgeBaseId, name, file, user);
    }

    @PostMapping(path = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocumentResponse uploadVersion(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal SafeAiUserPrincipal user) {
        return service.uploadVersion(knowledgeBaseId, documentId, file, user);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> downloadCurrent(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal SafeAiUserPrincipal user
    ) {
        return download(service.download(knowledgeBaseId, documentId, null, user));
    }

    @GetMapping("/{documentId}/versions/{versionId}/download")
    public ResponseEntity<Resource> downloadVersion(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal SafeAiUserPrincipal user
    ) {
        return download(service.download(knowledgeBaseId, documentId, versionId, user));
    }

    private ResponseEntity<Resource> download(KnowledgeDocumentService.Download value
    ) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(value.mediaType());
        } catch (InvalidMediaTypeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment().filename(value.filename(),
                StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(mediaType).contentLength(value.object().contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(value.object()
                        .resource());
    }
}
