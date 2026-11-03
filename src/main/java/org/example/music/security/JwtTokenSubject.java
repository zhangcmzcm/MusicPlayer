package org.example.music.security;

import org.example.music.user.domain.UserRole;

public record JwtTokenSubject(Integer userId, UserRole accountRole, long tokenVersion) { }
