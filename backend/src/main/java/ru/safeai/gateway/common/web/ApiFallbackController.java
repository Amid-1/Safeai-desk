package ru.safeai.gateway.common.web;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;

@RestController
public class ApiFallbackController {

    @RequestMapping("/api/**")
    public void apiNotFound() {
        throw new ResourceNotFoundException("API endpoint не найден");
    }
}
