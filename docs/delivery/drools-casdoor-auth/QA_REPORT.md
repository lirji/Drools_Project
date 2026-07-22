# QA Report

## Environment

- Date: 2026-07-22 Asia/Taipei
- Entry: `http://localhost:8095`
- IdP: local Casdoor `http://localhost:8000`
- Runtime: Docker console + decision + frontend nginx + MySQL; portal at `http://localhost:5274`
- Tenants/users: acme/act-alice and beta/act-bob (dev only)

## Acceptance Results

| AC | Result | Evidence |
| --- | --- | --- |
| AC-01 public auth-config | PASS | 200, `authEnabled=true`, localhost issuer, 8095 callback, acme/beta clients, `containsSecret=false` |
| AC-02 protected APIs/open UI | PASS | console anonymous 401; decision anonymous/header-only 401; UI/console health/decision direct health 200 |
| AC-03 audience and tenant envelope | PASS | integration JWT matrix 4/4; real acme decision 200; acme token + beta header 403 |
| AC-04 PKCE callback | PASS | real browser login through Casdoor and 8095 callback completed |
| AC-05 tenant isolation | PASS | acme UI create/list succeeded; beta search could not see acme activity |
| AC-06 logout/context/safe navigation | PASS | session token cleared, new context unauthenticated, state/returnTo tests pass |
| AC-07 auth-on deployment/JWKS | PASS | console/decision started; both fetched one signing key from host Casdoor; gateway healthy |
| AC-08 rollback | PASS | rendered Compose shows auth=false and dev-default=true for both services when override variables are set |
| AC-09 portal integration | PASS | served catalog points to 8095; valid public client auto-redirects to Casdoor; unknown client stays at Drools login |
| AC-10 CI and regression | PASS | GitHub Actions workflow added; local Maven/frontend/portal parity gates pass |

## Automated Results

| Suite/check | Result |
| --- | --- |
| `./mvnw package` | PASS — 115 tests, 0 failures, 0 errors, 3 skipped (common 63, console 40, decision 12) |
| decision auth focused integration | PASS — 4/4 |
| frontend Vitest | PASS — 13 files, 59 tests |
| frontend typecheck | PASS |
| frontend production build | PASS |
| project-portal tests | PASS — 13/13 |
| project-portal build | PASS |
| real Casdoor Playwright E2E | PASS — 12/12 |
| Compose default and rollback render | PASS |
| `bash -n` provision/deploy scripts | PASS |
| nginx config and callback hardening | PASS — `nginx -t`; no sentinel code in logs; no-store/no-referrer/text-html |
| `git diff --check` | PASS |

## Defects Found During QA

1. Decision security boundary missing — fixed and covered by automated tests.
2. Offline local image overlay initially could not see Maven target files — added a Dockerfile-specific ignore file; old images were preserved before replacement.
3. First callback log-hardening attempt internally redirected to the generic SPA location, so the sentinel still reached the log — replaced with exact file alias.
4. Exact alias initially emitted `application/octet-stream`, incompatible with `nosniff` — fixed to `text/html` and re-ran full OIDC E2E.

## Conclusion

**PASS / ready for localhost dev use.** All P0/P1 acceptance criteria passed and the auth-enabled stack remains running. Production release still requires production domains, clients, credentials, IdP policy and CSP configuration.
