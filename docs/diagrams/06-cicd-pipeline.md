# CI/CD Pipeline — GitHub Actions

## Pipeline Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                        GitHub Actions Pipeline                          │
│                      .github/workflows/ci-cd.yml                       │
└────────────────────────────────────────────────────────────────────────┘

TRIGGER: Pull Request (any branch → main or develop)
─────────────────────────────────────────────────────

  ┌─────────────────────────┐    ┌─────────────────────────────────────┐
  │     Build & Test        │    │        Terraform Validate           │
  │  ─────────────────────  │    │  ─────────────────────────────────  │
  │  ./mvnw verify          │    │  terraform fmt -check -recursive    │
  │  65 tests               │    │  terraform init -backend=false      │
  │  JaCoCo gate:           │    │  terraform validate                 │
  │    line   ≥ 70%         │    │                                     │
  │    branch ≥ 40%         │    │  ✓ No AWS credentials required      │
  │  Duration: ~2 min       │    │  Duration: ~13s                     │
  └─────────────────────────┘    └─────────────────────────────────────┘
           ↑ both must pass before PR can merge


TRIGGER: Push to main
──────────────────────

  ┌─────────────────────────────────────────────────────────────────┐
  │  Build & Test + Terraform Validate (same as above)              │
  └─────────────────────────────────┬───────────────────────────────┘
                                    │ both pass
                                    ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                    Docker Build & Push                          │
  │  ─────────────────────────────────────────────────────────────  │
  │  1. ./mvnw package -DskipTests                                  │
  │  2. docker build . -t emp:sha-{commit}                         │
  │  3. aws ecr get-login-password | docker login                  │
  │  4. docker push {account}.dkr.ecr.us-east-1.amazonaws.com/emp  │
  │                                                                 │
  │  Tags generated:                                                │
  │    · sha-abc1234 (commit SHA)                                   │
  │    · main (branch name)                                         │
  └─────────────────────────────────┬───────────────────────────────┘
                                    │
                                    ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                   Terraform Plan (staging)                      │
  │  ─────────────────────────────────────────────────────────────  │
  │  terraform init                                                 │
  │  terraform plan                                                 │
  │    -var-file=environments/dev.tfvars                            │
  │    -var="app_image={ecr_image}:{tag}"                           │
  │    -out=tfplan                                                  │
  │                                                                 │
  │  Plan artifact uploaded (retention: 5 days)                     │
  └─────────────────────────────────────────────────────────────────┘


TRIGGER: Tag v* (e.g. v1.0.0, v1.2.3)
───────────────────────────────────────

  ┌─────────────────────────────────────────────────────────────────┐
  │  All previous jobs...                                           │
  └─────────────────────────────────┬───────────────────────────────┘
                                    │
                                    ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │              Deploy Production                                  │
  │  ─────────────────────────────────────────────────────────────  │
  │                                                                 │
  │  ⚠ REQUIRES MANUAL APPROVAL                                     │
  │    GitHub Environment: production                               │
  │    Configured reviewers must approve before job runs            │
  │                                                                 │
  │  Steps:                                                         │
  │  1. terraform init                                              │
  │  2. terraform apply                                             │
  │       -var-file=environments/prod.tfvars                        │
  │       -var="app_image={ecr_image}:{tag}"                        │
  │       -auto-approve                                             │
  │  3. aws ecs wait services-stable                                │
  │       --cluster emp-prod-cluster                                │
  │       --services emp-prod-service                               │
  │  4. Smoke test:                                                 │
  │       curl -f https://{alb_dns}/actuator/health                │
  │       → {"status":"UP"} ✓                                      │
  └─────────────────────────────────────────────────────────────────┘
```

## Job Summary

| Job | Trigger | Duration | AWS Creds |
|---|---|---|---|
| Build & Test | All | ~2 min | ✗ Not needed |
| Terraform Validate | All | ~13s | ✗ Not needed |
| Docker Build & Push | `main`, `v*` | ~3 min | ✓ Required |
| Terraform Plan | `main` | ~1 min | ✓ Required |
| Deploy Production | `v*` tags | ~5 min | ✓ Required + Manual Approval |

## Branch Strategy

```
main ←─── develop ←─── feature/TICK-XXX-description
  │                         │
  │    (PR required)         │ (PR required)
  │                         │
  └──── release tag v*       └── individual feature work
         triggers deploy
```

## Secrets Required

```
GitHub → Settings → Secrets and variables → Actions:

AWS_ACCESS_KEY_ID       IAM user with ECR push + ECS deploy permissions
AWS_SECRET_ACCESS_KEY   Corresponding secret key
```
