package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseAccessResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseMemberPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseMemberResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeBasePageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeMemberCandidateResponse;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeAccessService knowledgeAccessService;

    @GetMapping
    public KnowledgeBasePageResponse findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,
            @RequestParam(defaultValue = "50")
            @Min(1)
            @Max(100)
            int size
    ) {
        return KnowledgeBasePageResponse.from(
                knowledgeBaseService.findAll(
                        currentUser,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{knowledgeBaseId}")
    public KnowledgeBaseResponse findById(
            @PathVariable UUID knowledgeBaseId,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.findById(
                knowledgeBaseId,
                currentUser
        );
    }

    @GetMapping("/{knowledgeBaseId}/access")
    public KnowledgeBaseAccessResponse access(
            @PathVariable UUID knowledgeBaseId,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        KnowledgeAccessService.Access access =
                knowledgeAccessService.requireAccess(
                        knowledgeBaseId,
                        currentUser,
                        KnowledgeBaseAccessLevel.VIEWER
                );

        return KnowledgeBaseAccessResponse.from(
                knowledgeBaseId,
                access
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseResponse create(
            @Valid
            @RequestBody
            CreateKnowledgeBaseRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.create(
                request,
                currentUser
        );
    }

    @PatchMapping("/{knowledgeBaseId}")
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseResponse update(
            @PathVariable UUID knowledgeBaseId,
            @Valid
            @RequestBody
            UpdateKnowledgeBaseRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.update(
                knowledgeBaseId,
                request,
                currentUser
        );
    }

    @GetMapping("/{knowledgeBaseId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseMemberPageResponse findMembers(
            @PathVariable UUID knowledgeBaseId,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,
            @RequestParam(defaultValue = "50")
            @Min(1)
            @Max(100)
            int size
    ) {
        return KnowledgeBaseMemberPageResponse.from(
                knowledgeBaseService.findMembers(
                        knowledgeBaseId,
                        currentUser,
                        page,
                        size
                )
        );
    }

    @GetMapping("/member-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public List<KnowledgeMemberCandidateResponse> searchMemberCandidates(
            @RequestParam(defaultValue = "")
            String query,
            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(50)
            int limit,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.searchMemberCandidates(
                query,
                limit,
                currentUser
        );
    }

    @PostMapping("/{knowledgeBaseId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseMemberResponse addMember(
            @PathVariable UUID knowledgeBaseId,
            @Valid
            @RequestBody
            CreateKnowledgeBaseMemberRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.addMember(
                knowledgeBaseId,
                request,
                currentUser
        );
    }

    @PatchMapping("/{knowledgeBaseId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseMemberResponse updateMember(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID userId,
            @Valid
            @RequestBody
            UpdateKnowledgeBaseMemberRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return knowledgeBaseService.updateMember(
                knowledgeBaseId,
                userId,
                request,
                currentUser
        );
    }

    @DeleteMapping("/{knowledgeBaseId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void removeMember(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID userId,
            @RequestParam
            @Min(0)
            long expectedVersion,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        knowledgeBaseService.removeMember(
                knowledgeBaseId,
                userId,
                expectedVersion,
                currentUser
        );
    }
}
