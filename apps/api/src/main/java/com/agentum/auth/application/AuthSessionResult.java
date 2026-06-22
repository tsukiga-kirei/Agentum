package com.agentum.auth.application;

import com.agentum.auth.interfaces.LoginResponse;

public record AuthSessionResult(LoginResponse response, IssuedRefreshToken refreshToken) {
}
