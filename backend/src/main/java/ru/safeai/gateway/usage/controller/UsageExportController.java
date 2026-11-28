// backend/src/main/java/ru/safeai/gateway/usage/controller/UsageExportController.java
package ru.safeai.gateway.usage.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageExportRequest;
import ru.safeai.gateway.usage.service.UsageExportService;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/usage/exports")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsageExportController {
    private final UsageExportService service;

    public UsageExportController(UsageExportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<UsageExportService.Status> start(@RequestBody UsageExportRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.start(request, actor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UsageExportService.Status> status(@PathVariable UUID id,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(id, actor));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"safeai-usage.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.download(id, actor));
    }
}
