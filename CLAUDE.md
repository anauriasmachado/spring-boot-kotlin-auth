# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kotlin 2.3 / Spring Boot 4.1 REST API on JVM 21, backed by MongoDB Atlas. JWT auth plus a notes CRUD resource. Built with Gradle (Kotlin DSL); use the wrapper.

## Commands

```bash
./gradlew build                                            # compile + test
./gradlew bootRun                                          # run the API on :8085 (needs env vars below)
./gradlew test --tests 'com.example.crash_course.security.*'   # fast unit tests — no Mongo, no env vars
./gradlew test --tests '*JwtServiceTest'                   # a single test class
./gradlew test --tests '*AuthServiceTest.login*'            # a single test method (backtick names: match a prefix, quote the pattern)
```

Test reports land in `build/reports/tests/test/index.html`; raw XML in `build/test-results/test/`.

## Required configuration

`src/main/resources/application.properties` interpolates two values that have no defaults:

- `JWT_SECRET_BASE64` — base64 text whose **decoded** form is at least 32 bytes (HS256 via `Keys.hmacShaKeyFor`). A short or non-base64 value fails at `JwtService` construction, i.e. at startup.
- `password` — the MongoDB Atlas user password, substituted into `spring.data.mongodb.uri`.

Without them the context cannot start. This is why bare `./gradlew test` **fails**: `CrashCourseApplicationTests.contextLoads` is a `@SpringBootTest` and dies on `Could not resolve placeholder 'JWT_SECRET_BASE64'`. That failure is environmental, not a regression — prefer the `security.*` filter for the normal edit/test loop.

## Architecture

Layering is `controllers/` → `security/` services → `database/model/` repositories. There is no service layer for notes: `NoteController` talks to `NoteRepository` directly, while auth logic lives in `security/AuthService`.

**Auth flow.** `AuthController` (`/auth/register|login|refresh`) delegates to `AuthService`. Tokens are stateless JWTs from `JwtService`, distinguished only by a `type` claim (`"access"` 15 min, `"refresh"` 30 days) — the claim is the sole thing stopping a refresh token from being used as an access token, so preserve that check when touching validation.

**Refresh-token rotation.** Refresh tokens are stateless JWTs *and* tracked server-side: `AuthService` stores a SHA-256 + base64 digest of each one in the `refresh_token` collection. `refresh()` validates the JWT, looks up the digest, deletes it, then issues and stores a new pair. Both hashing sites go through the private `hashToken`, so store and lookup must stay in sync. `RefreshToken.expiresAt` carries a Mongo `@Indexed(expireAfter = "0s")` TTL, so expired rows self-delete.

**Request authentication.** `JwtAuthFilter` reads the `Authorization: Bearer` header and puts the **user id hex string** into the `SecurityContext` as the principal. `NoteController` casts `authentication.principal` to `String` and wraps it in an `ObjectId` for ownership checks — that contract between filter and controllers is implicit and untyped, so changing the principal breaks notes at runtime, not compile time.

**Ownership** is enforced per-handler, not by Spring Security: `NoteController.deleteById` compares `note.ownerId` against the principal itself.

### Known rough edges

Present in `main`; useful context before assuming code is correct.

- `SecurityConfig` builds a chain with only CSRF-off and stateless sessions — no `authorizeHttpRequests` rules and no explicit registration of `JwtAuthFilter`. The filter runs only because a `Filter` `@Component` is auto-registered with the servlet container, so it populates the context but nothing enforces authentication.
- `RefreshTokenRepository.deleteByUserId(userId, hashedToken)` takes two parameters while the derived-query name mentions only `UserId`.
- `JwtService.validateAccessToken` checks the `type` claim but not expiry; it is currently safe only because parsing throws on expired tokens. `validateRefreshToken` checks expiry explicitly.
- `JwtService.generateToken` derives the JWT from subject/type/`iat`/`exp` only, so two calls in the same millisecond produce identical tokens — rotation can hand back the token it just revoked. A `jti` claim would fix it.
- `AuthService.register` has a dangling `val userWithHashedPassword =` before its `return` (compiles because `return` is typed `Nothing`).

## Testing conventions

`security/JwtServiceTest` and `security/AuthServiceTest` are plain JUnit 5 + `kotlin-test` unit tests. `AuthServiceTest` mocks only the two repository *interfaces* with MockK and uses real `JwtService` and `HashEncoder` instances, so assertions run against genuine JWT signing and BCrypt rather than stubs. Test names are backticked sentences. Keep new tests in this style — Mongo-free — so the fast loop stays fast.

## Pull request workflow (GitHub CLI)

Remote is `git@github.com:anauriasmachado/spring-boot-kotlin-auth.git`; PRs target `main`. `gh` is installed. If `gh auth status` reports no host, the login is interactive — ask the user to run `! gh auth login` rather than attempting it from a tool call.

**Validate before pushing.** Run this gate and require a clean exit; do not push or open a PR on a red gate.

```bash
./gradlew build -x test && ./gradlew test --tests 'com.example.crash_course.security.*'
```

Two steps, deliberately. `-x test` compiles and assembles without running tests, then the filtered run exercises the Mongo-free suite. **Do not use a bare `./gradlew build` or `./gradlew test` as the gate** — both invoke `CrashCourseApplicationTests.contextLoads`, which always fails without `JWT_SECRET_BASE64` and `password`, so the gate would reject every branch. Only when both env vars are exported *and* Atlas is reachable is full `./gradlew build` the stronger gate.

**Then open the PR:**

```bash
git branch --show-current                      # never commit to main; branch first if on it
git status --short                             # confirm only intended files
git push -u origin "$(git branch --show-current)"
gh pr create --base main --title '...' --body '...'
gh pr view --web                               # hand the user the URL
```

Notes for this repo:

- `build/`, `.gradle/`, `.kotlin/` and `.idea/` are gitignored and nothing under them is tracked, so staging broadly is safe. Note `HELP.md` is gitignored too, despite being present on disk.
- `src/main/resources/application.properties` is tracked and holds a real Atlas URI and username; the password and JWT secret come from `${password}` / `${JWT_SECRET_BASE64}` at runtime. Never resolve those placeholders into the committed file.
- Commit and push only when the user asks. Opening a PR is outward-facing: confirm before creating one unless already told to proceed.
- A branch touching `security/` should carry its `JwtServiceTest` / `AuthServiceTest` updates in the same PR; those run in the gate above, so a security change with no test movement is worth flagging in the PR body.
