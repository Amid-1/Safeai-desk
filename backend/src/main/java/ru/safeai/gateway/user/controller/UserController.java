package ru.safeai.gateway.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserService;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping
    public List<UserResponse> findAll() {
        return userService.findAll();
    }
}