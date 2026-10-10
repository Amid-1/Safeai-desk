package ru.safeai.gateway.usage.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.usage.service.UsageForcedRebuildService;

import java.time.LocalDate;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/usage/rebuild")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UsageRebuildController {

    private final UsageForcedRebuildService rebuildService;

    public UsageRebuildController(
            UsageForcedRebuildService rebuildService
    ) {
        this.rebuildService = Objects.requireNonNull(rebuildService);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestRebuild(
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        rebuildService.request(date);
    }
}