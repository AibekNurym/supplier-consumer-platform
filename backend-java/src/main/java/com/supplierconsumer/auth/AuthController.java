package com.supplierconsumer.auth;

import com.supplierconsumer.auth.dto.AuthDtos;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.WireError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/auth} -- registration, sign-in and the signed-in user's own profile.
 *
 * <p>Handlers that take a {@link Principals.Company} are the authenticated ones; the rest are
 * public, which is how the original mounts its middleware.
 *
 * <p>Request bodies are optional and their fields nullable on purpose. {@code express.json()}
 * ignores a body it cannot parse and leaves an empty object behind, so the handler's own check
 * produces the 400. Requiring the body would make Spring answer 415 or 400 in a different shape
 * before the handler ran.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @WireError(message = "Internal server error during registration")
    public ResponseEntity<ApiResponse> register(
            @RequestBody(required = false) AuthDtos.RegisterRequest request,
            HttpServletRequest http) {

        AuthDtos.RegisterPayload payload = service.register(orEmpty(request), http);
        return ResponseEntity.status(201).body(ApiResponse.ok()
                .message("User registered successfully")
                .data(payload));
    }

    @PostMapping("/login")
    @WireError(message = "Internal server error during login")
    public ApiResponse login(@RequestBody(required = false) AuthDtos.LoginRequest request,
                             HttpServletRequest http) {

        AuthDtos.LoginPayload payload = service.login(
                request == null ? new AuthDtos.LoginRequest(null, null) : request, http);
        return ApiResponse.ok().message("Login successful").data(payload);
    }

    @PostMapping("/refresh-token")
    @WireError(message = "Internal server error during token refresh")
    public ApiResponse refresh(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                               HttpServletRequest http) {

        AuthDtos.RefreshPayload payload = service.refresh(
                request == null ? new AuthDtos.RefreshRequest(null) : request, http);
        return ApiResponse.ok().message("Token refreshed successfully").data(payload);
    }

    @PostMapping("/logout")
    @WireError(message = "Internal server error during logout")
    public ApiResponse logout(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                              Principals.Company user,
                              HttpServletRequest http) {

        service.logout(request == null ? null : request.refreshToken(), user, http);
        return ApiResponse.ok().message("Logout successful");
    }

    @GetMapping("/profile")
    public ApiResponse profile(Principals.Company user) {
        return ApiResponse.ok().data(service.profile(user));
    }

    /** Returns the raw updated row, so this response is snake_case unlike the GET above. */
    @PutMapping("/profile")
    public ApiResponse updateProfile(Principals.Company user,
                                     @RequestBody(required = false) AuthDtos.UpdateProfileRequest request,
                                     HttpServletRequest http) {

        Map<String, Object> updated = service.updateProfile(user,
                request == null ? new AuthDtos.UpdateProfileRequest(null, null, null) : request, http);
        return ApiResponse.ok().message("Profile updated successfully").data(updated);
    }

    @PutMapping("/change-password")
    public ApiResponse changePassword(Principals.Company user,
                                      @RequestBody(required = false) AuthDtos.ChangePasswordRequest request,
                                      HttpServletRequest http) {

        service.changePassword(user,
                request == null ? new AuthDtos.ChangePasswordRequest(null, null) : request, http);
        return ApiResponse.ok().message("Password changed successfully");
    }

    private AuthDtos.RegisterRequest orEmpty(AuthDtos.RegisterRequest request) {
        return request == null
                ? new AuthDtos.RegisterRequest(null, null, null, null, null, null)
                : request;
    }
}
