# Track Authentication Principal Regression Repair Design

Date: 2026-10-28

## Purpose

Repair the authentication-principal regression introduced by commit `cdbea99` that causes authenticated requests to the following endpoints to fail with HTTP 500:

- `PATCH /api/v1/tracks/{trackId}/draft`
- `POST /api/v1/track-imports`

This is a single regression repair exception to the normal one-endpoint-per-change rule because one authentication contract change caused both failures.

## Current Failure

`TokenVersionAuthenticationFilter` replaces a validated `JwtAuthenticationToken` with a `UsernamePasswordAuthenticationToken` whose principal is `AuthenticatedUserPrincipal`. Both track controllers still declare `@AuthenticationPrincipal Jwt jwt` and call `jwt.getSubject()`. Spring resolves the mismatched principal type to `null`, so the controller throws `NullPointerException` and the request is translated to HTTP 500.

## Chosen Approach

Use the production authentication principal consistently in both controllers:

```java
@AuthenticationPrincipal AuthenticatedUserPrincipal actor
```

Each controller obtains the user ID through `actor.userId()` and passes it to the existing service method without changing the service signature or any request/response contract.

Rejected alternatives:

- Changing `TokenVersionAuthenticationFilter` or `SecurityConfig` to preserve `JwtAuthenticationToken` would alter the hardened authentication contract and exceed the repair scope.
- Adding a custom dual-principal resolver would preserve two identity sources and add unnecessary security infrastructure.

## Components

### Controllers

`TrackDraftController` and `TrackImportController` change only their authentication parameter type and local user-ID extraction. Validation, authorization annotations, rate limiting order, service calls, response mapping, status codes, and error codes remain unchanged.

### Controller Tests

`TrackDraftControllerTest` and `TrackImportControllerTest` inject `UsernamePasswordAuthenticationToken` containing `AuthenticatedUserPrincipal`, matching the production post-filter principal shape. Each test class documents that `SecurityMockMvcRequestPostProcessors.authentication(...)` bypasses `TokenVersionAuthenticationFilter`, so these tests validate controller contracts but not the real token filter chain.

### Real-Chain Integration Test

Add `TrackAdminAuthenticationIntegrationTest` using `@SpringBootTest`, `MockMvc`, real JWT issuance through `JwtTokenService`, and Testcontainers for MySQL 8.4, Redis 7.4, and the repository's pinned MinIO image. The test is enabled only when Docker is available and `MINIO_INTEGRATION_TESTS=true`.

Required cases:

1. Administrator PATCH request updates an existing draft and returns HTTP 200.
2. PATCH request without authentication returns HTTP 401.
3. PATCH request with a valid non-administrator token returns HTTP 403.
4. Administrator POST request imports a valid tagged MP3 and returns HTTP 201.
5. POST request without authentication returns HTTP 401.
6. POST request with a valid non-administrator token returns HTTP 403.

The two successful cases execute the real `BearerTokenAuthenticationFilter`, `TokenVersionAuthenticationFilter`, controller, service, MySQL persistence, Redis rate limiting, audio parsing, and MinIO storage path. Reintroducing `@AuthenticationPrincipal Jwt` in either controller makes the corresponding successful case fail with HTTP 500.

## Error Handling

No error contract changes are required. Existing `GlobalExceptionHandler` behavior remains authoritative for validation, authorization, conflict, rate-limit, and storage failures. The regression-specific outcome is that valid authenticated administrator requests no longer reach the fallback HTTP 500 handler because the controller receives a non-null production principal.

## Database and Configuration

No database migration, dependency, application configuration, security configuration, or OpenAPI change is required.

## Documentation

`docs/api/track/import-track.md` and `docs/api/track/update-track-draft.md` describe unchanged public contracts. Current curl examples use a non-secret sample JWT value and do not claim that controllers read user IDs directly from JWT claims. Repository inspection found no wording that describes the old principal extraction behavior, so no API documentation change is required.

## Scope

Allowed files:

- `src/main/java/org/example/music/track/controller/TrackDraftController.java`
- `src/main/java/org/example/music/track/controller/TrackImportController.java`
- `src/test/java/org/example/music/track/controller/TrackDraftControllerTest.java`
- `src/test/java/org/example/music/track/controller/TrackImportControllerTest.java`
- `src/test/java/org/example/music/track/TrackAdminAuthenticationIntegrationTest.java`
- `docs/api/track/*.md` only if an authentication-principal implementation statement must be corrected

Explicit non-goals:

- No security filter or security configuration changes
- No service, DTO, repository, entity, migration, dependency, or unrelated refactoring
- No behavior changes beyond authentication-principal extraction

## Verification

1. Search the repository and confirm no `@AuthenticationPrincipal Jwt` usage remains.
2. Run the two affected controller test classes.
3. Run `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat check`.
4. Require zero failures and no skipped Docker/Testcontainers tests when Docker is available.
5. Inspect tracked and untracked changes and confirm they remain within the allowed scope.
6. Report exact pass, failure, and skip counts, including skipped test identifiers and reasons.

## Acceptance Criteria

- Both endpoints process valid administrator Bearer Token requests without HTTP 500 or `NullPointerException`.
- Each endpoint returns 401 without authentication and 403 for a valid non-administrator token.
- The real-chain test fails if either controller is restored to JWT-principal extraction.
- No `@AuthenticationPrincipal Jwt` usage remains.
- `MINIO_INTEGRATION_TESTS=true .\gradlew.bat check` passes with required Testcontainers tests executed.
- Public API behavior and documentation contracts remain unchanged.
- The final diff contains no out-of-scope changes.

