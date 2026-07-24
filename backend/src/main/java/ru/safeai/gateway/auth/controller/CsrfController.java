package ru.safeai.gateway.auth.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.auth.dto.CsrfTokenResponse;

@RestController
public class CsrfController {

    @GetMapping("/api/auth/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(
            CsrfToken csrfToken
    ) {
        CsrfTokenResponse body = new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
