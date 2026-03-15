# Git Branching Strategy

## Modelo: GitHub Flow con feature branches

Cada feature de la plataforma vive en su propia rama, desarrollada contra `develop`.

## Estructura de ramas

```
main
 └── develop
      ├── feature/TICK-001-project-setup
      ├── feature/TICK-002-event-management
      ├── feature/TICK-003-ticket-inventory
      ├── feature/TICK-004-ticket-reservation
      ├── feature/TICK-005-purchase-order-processing
      ├── feature/TICK-006-order-status-query
      ├── feature/TICK-007-expired-reservation-release
      ├── feature/TICK-008-reactive-availability-endpoint
      ├── feature/TICK-009-global-error-handler
      ├── feature/TICK-010-observability
      ├── feature/TICK-011-integration-tests
      ├── hotfix/TICK-XXX-descripcion
      └── chore/TICK-XXX-descripcion
```

## Convención de nombres

| Tipo | Patrón | Ejemplo |
|---|---|---|
| `feature` | `feature/TICK-{n}-{desc}` | `feature/TICK-002-event-management` |
| `hotfix` | `hotfix/TICK-{n}-{desc}` | `hotfix/TICK-099-fix-oversell` |
| `chore` | `chore/TICK-{n}-{desc}` | `chore/TICK-015-upgrade-aws-sdk` |
| `refactor` | `refactor/TICK-{n}-{desc}` | `refactor/TICK-020-extract-service` |
| `docs` | `docs/TICK-{n}-{desc}` | `docs/TICK-030-architecture` |

**Reglas:**
- Siempre `kebab-case` (minúsculas, guiones)
- Siempre con ID de ticket `TICK-{n}`
- Descripción máximo 5 palabras

## Convención de commits

```
<tipo>(scope): <descripción corta>

[cuerpo opcional]

TICK-XXX
```

**Tipos:** `feat` · `fix` · `test` · `refactor` · `chore` · `docs` · `perf`

**Ejemplos:**
```
feat(event): add CreateEventUseCase with DynamoDB adapter

TICK-002

---

fix(reservation): prevent race condition in concurrent ticket reservation

Two concurrent requests could bypass the version check.
Added stronger ConditionExpression: version + status.

TICK-004
```

## Flujo PR

```
feature/TICK-XXX  ──► develop  ──► main
```

1. Abrir PR: `feature/TICK-XXX` → `develop`
2. Checks requeridos: tests ✅ · cobertura ≥ 90% ✅ · 1 aprobación ✅
3. Squash merge con mensaje descriptivo
4. Borrar rama feature después del merge

## Comandos del día a día

```bash
# Iniciar feature
git checkout develop && git pull origin develop
git checkout -b feature/TICK-002-event-management

# Trabajo diario
git add . && git commit -m "feat(event): add Event domain record"
git push origin feature/TICK-002-event-management

# Mantener sincronizado con develop
git fetch origin && git rebase origin/develop

# Después del merge del PR
git checkout develop && git pull origin develop
git branch -d feature/TICK-002-event-management
```
