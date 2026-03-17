# Git Branching Strategy — Microservices v2

## Model: GitHub Flow with feature branches

All work targets `main` via short-lived feature branches. The v2 monorepo lives in `feat/microservices-mono-repo-architecture-v2`, merged to `main` as the new baseline.

## Branch Structure

```
main
 ├── feat/microservices-mono-repo-architecture-v2   ← v2 architecture baseline
 ├── feature/TICK-{n}-{desc}
 ├── hotfix/TICK-{n}-{desc}
 └── chore/TICK-{n}-{desc}
```

## Naming Convention

| Type | Pattern | Example |
|---|---|---|
| `feature` | `feature/TICK-{n}-{desc}` | `feature/TICK-012-jwt-authentication` |
| `hotfix` | `hotfix/TICK-{n}-{desc}` | `hotfix/TICK-099-fix-oversell` |
| `chore` | `chore/TICK-{n}-{desc}` | `chore/TICK-015-upgrade-aws-sdk` |
| `refactor` | `refactor/TICK-{n}-{desc}` | `refactor/TICK-020-write-sharding` |
| `docs` | `docs/TICK-{n}-{desc}` | `docs/TICK-030-architecture` |

**Rules:**
- Always `kebab-case` (lowercase, hyphens)
- Always include ticket ID `TICK-{n}`
- Description max 5 words

## Commit Convention

```
<type>(scope): <short description>

[optional body]

TICK-XXX
```

**Types:** `feat` · `fix` · `test` · `refactor` · `chore` · `docs` · `perf` · `ci`

**Scopes:** `event-svc` · `reservation-svc` · `order-svc` · `consumer-svc` · `shared` · `infra` · `ci`

**Examples:**
```
feat(reservation-svc): add conditional write retry with exponential backoff

Uses reactor.util.retry.Retry.backoff(3, 100ms) to handle concurrent
DynamoDB conditional check failures (ConcurrentModificationException).

TICK-013

---

fix(consumer-svc): replace SqsMessageDeletionPolicy with ON_SUCCESS ack mode

Spring Cloud AWS 3.x removed SqsMessageDeletionPolicy enum.
Replaced with acknowledgementMode = SqsListenerAcknowledgementMode.ON_SUCCESS.

TICK-014

---

ci: add multi-service build matrix for 4 microservices

TICK-015
```

## PR Flow

```
feature/TICK-XXX  ──► main
```

1. Open PR: `feature/TICK-XXX` → `main`
2. Required checks: all 4 service tests ✅ · JaCoCo ≥90% line / ≥85% branch ✅ · Docker build ✅ · 1 approval ✅
3. Squash merge with descriptive message
4. Delete feature branch after merge

## Daily Commands

```bash
# Start feature
git checkout main && git pull origin main
git checkout -b feature/TICK-012-jwt-authentication

# Daily work
git add -p && git commit -m "feat(reservation-svc): add JWT filter"
git push origin feature/TICK-012-jwt-authentication

# Keep in sync with main
git fetch origin && git rebase origin/main

# After PR merge
git checkout main && git pull origin main
git branch -d feature/TICK-012-jwt-authentication
```

## Service Scope Tags

When changes touch multiple services, list scopes:

```
feat(event-svc,shared): add Write Sharding support for high-demand events

TICK-016
```

## Ticket Backlog — v2 Next Steps

| Ticket | Scope | Priority |
|---|---|---|
| TICK-012 | JWT authentication (Spring Security + Cognito) | High |
| TICK-013 | Rate limiting (WebFlux filter — 100 req/min/IP) | High |
| TICK-014 | DynamoDB Streams + Lambda for expiry compensation | High |
| TICK-015 | AWS X-Ray distributed tracing | Medium |
| TICK-016 | Virtual waiting room (SQS queue for peak events) | Medium |
| TICK-017 | CQRS read model with ElastiCache for availability | Medium |
| TICK-018 | Contract testing with Pact | Medium |
| TICK-019 | Canary deployments with CodeDeploy | Medium |
