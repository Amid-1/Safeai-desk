package ru.safeai.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUsageService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary() {
        return chatMessageRepository.findUsageSummary();
    }
}