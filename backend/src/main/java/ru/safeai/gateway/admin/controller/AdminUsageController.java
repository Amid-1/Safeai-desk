package ru.safeai.gateway.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;

import java.util.List;

@RestController
@RequestMapping("/api/admin/usage-summary")
@RequiredArgsConstructor
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    @GetMapping
    public List<UsageSummaryResponse> getUsageSummary() {
        return adminUsageService.getUsageSummary();
    }
}