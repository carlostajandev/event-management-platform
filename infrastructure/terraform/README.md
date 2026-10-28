# Infrastructure (Terraform)

The whole AWS deployment for one environment: network, data, edge, compute, alarms and the CI deploy role.
`dev` and `prod` are the same code with different variables and separate state.

```
                    Internet
                       │
              ┌────────▼────────┐
              │  WAF (rate/IP,  │
              │  bad inputs)    │
              │  ALB  :80/:443  │   public subnets (2 AZ)
              └────────┬────────┘
   ────────────────────┼──────────────────────────────────────────
                       │ :8080 (ALB security group only)
              ┌────────▼────────┐        ┌──────────────────┐
              │ ECS api service │        │ ECS worker       │   private subnets (2 AZ)
              │ 2..N tasks      │        │ 1..N tasks       │   no route to the internet
              └───┬─────────────┘        └──┬───────────┬───┘
                  │ gateway endpoint        │           │ interface endpoint
              ┌───▼──────────────────────────▼──┐   ┌────▼─────┐
              │ DynamoDB: events, orders,       │   │ SQS      │
              │ idempotency, audit, outbox      │   │ orders + │
              └─────────────────────────────────┘   │ DLQ      │
                                                    └──────────┘
   ECR, CloudWatch Logs: interface endpoints · S3 (ECR layers): gateway endpoint
```

## Layout

| Path | Contents |
|---|---|
| `main.tf`, `variables.tf`, `outputs.tf`, `versions.tf` | Root module: wires the modules, S3 backend with native locking |
| `modules/network` | VPC, 2 public + 2 private subnets, VPC endpoints, default SG locked, rejected-traffic flow logs |
| `modules/data` | The five DynamoDB tables (same schema as `infrastructure/local/dynamodb-init.sh`), orders queue + DLQ |
| `modules/edge` | ALB, target group, listeners, WAF |
| `modules/compute` | ECR, ECS cluster, one task definition and service per role, IAM, autoscaling |
| `modules/observability` | SNS topic and alarms |
| `modules/ci` | GitHub OIDC provider and the deploy role |
| `environments/` | `<env>.tfvars` (sizing) and `<env>.backend.hcl` (state location) |
| `bootstrap/` | The state bucket, created once per account |
| `modules/*/tests` | `terraform test` with a mocked provider: runs in CI without an AWS account |

## Decisions

### Networking

- **No NAT gateway.** Tasks run in private subnets whose route table has no default route. Everything they
  call has a VPC endpoint: DynamoDB and S3 (gateway, free), ECR, CloudWatch Logs and SQS (interface). A task
  cannot reach an arbitrary internet host, which closes an exfiltration path, and there is no NAT data charge.
  The trade-off is cost at idle: eight interface endpoint-AZs (~$58/month) cost more than one NAT gateway, but
  one NAT is a single-AZ dependency and charges for every gigabyte. A test asserts the private route table
  stays empty.
- **Two AZs.** The ALB and both services span two AZs; prod keeps two api tasks at minimum, one per AZ.
- **Security groups by reference.** The api accepts port 8080 only from the ALB's security group. The worker
  accepts nothing. Both allow outbound 443 only.

### Security

- **One IAM role per role.** Built from the calls the code makes (see `modules/compute/iam.tf`): the api has no
  SQS permission and cannot query the holds index; the worker can only put audit items, so it cannot create
  events or orders. No action wildcards. Tests guard these three properties.
- **No long-lived keys.** Tasks use their task role; CI uses GitHub OIDC. The deploy role trusts only jobs
  of this repository running in the matching GitHub environment, so a pull request cannot deploy, and prod
  deploys pass the environment's required reviewers. It can push to one ECR repository, register task
  definitions, update the two services and pass only the three task roles to ECS.
- **Edge.** WAF rate-based rule per IP and the AWS known-bad-inputs rule group (Log4j-style payloads). The ALB
  drops invalid headers. HTTPS with TLS 1.3 when a certificate is given; this assessment has no domain, so it
  serves HTTP. The application's own per-address limit is off in AWS because behind the ALB it would see
  the ALB's address.
- **Containers.** Read-only root filesystem with a writable `/tmp`, non-root user (Dockerfile), immutable
  image tags, scan on push.
- **Data.** Encrypted at rest with AWS owned/managed keys (DynamoDB, SQS, ECR, logs): no key policy to run,
  no KMS cost. Deletion protection and point-in-time recovery in prod. SQS denies non-TLS access.
- **Checkov runs in CI.** Accepted findings are skipped inline with the reason next to the resource, so a new
  finding fails the build. The notable one: encrypting the SNS topic with `aws/sns` would stop CloudWatch
  alarms from publishing, because that key's policy cannot grant CloudWatch.

### Scalability

| Role | Signal | Policy |
|---|---|---|
| api | ALB requests per target, CPU 60 % | Target tracking, scale in after 5 min |
| worker | Visible messages in the orders queue | Step scaling: +1 above 100, +3 above 1,000; −1 after 15 quiet minutes |

- The worker never scales below one task: it also runs the outbox relay and the expiry job. Running several is
  safe because every transition is conditional.
- Worker tasks beyond the baseline run on Fargate Spot. An interrupted task loses nothing: unacknowledged
  messages become visible again and processing is idempotent.
- DynamoDB is on-demand. The first limit under a flash sale is the event's inventory item, roughly 500
  orders/s per event (see `load-test/RESULTS.md`), watched by the `events-write-throttling` alarm.
- Deployments are rolling (100 % / 200 %) with the ECS deployment circuit breaker rolling back tasks that
  never become healthy.

### Environment isolation

- Separate state per environment (`environments/<env>.backend.hcl`), so a plan for dev cannot touch prod.
- Every name is prefixed `ticketing-<env>`; the recommended setup is one AWS account per environment, and the
  code works either way (`create_oidc_provider` handles the account-wide OIDC provider).
- Differences live only in tfvars: sizing, retention, container insights, data protection.

### Observability

Alarms go to an SNS topic (optional e-mail subscription):

| Alarm | Why |
|---|---|
| `api-5xx` | Unexpected errors reach buyers |
| `api-latency-p99` | p99 over 1 s: the sale feels broken before anything fails |
| `api-unhealthy-tasks` | A task fails readiness |
| `events-write-throttling` | The inventory item hit its per-item limit |
| `orders-write-throttling` | Orders table throttled |
| `orders-queue-lag` | Orders wait over 2 minutes; holds may expire before processing |
| `orders-dlq-not-empty` | A message failed 3 times and needs a look |

Logs go to CloudWatch with 7 days of retention in dev and 30 in prod.

## Cost (approximate, us-east-1 list prices, idle)

| Item | dev | prod |
|---|---|---|
| Fargate api (0.5 vCPU/1 GB ×1 · 1 vCPU/2 GB ×2) | ~$18 | ~$72 |
| Fargate worker (0.25/0.5 · 0.5/1) | ~$9 | ~$18 |
| ALB (before LCUs) | ~$16 | ~$16 |
| Interface endpoints (4 services × 2 AZ) | ~$58 | ~$58 |
| WAF (ACL + 2 rules, before requests) | ~$7 | ~$7 |
| DynamoDB, SQS, logs | pay per use, near zero idle | pay per use |
| **Total at idle** | **~$110/month** | **~$170/month** |

To save in dev: one AZ for the interface endpoints, or tear it down (`protect_data = false` in dev).

## Deploying

```bash
cd infrastructure/terraform

# 1. Once per account: the state bucket
terraform -chdir=bootstrap init && terraform -chdir=bootstrap apply -var environment=dev

# 2. The environment
terraform init -backend-config=environments/dev.backend.hcl
terraform apply -var-file=environments/dev.tfvars

# 3. First image (tag "bootstrap", the value in dev.tfvars); CI pushes every later one
aws ecr get-login-password | docker login --username AWS --password-stdin <ecr_repository_url host>
docker build -t <ecr_repository_url>:bootstrap ../.. && docker push <ecr_repository_url>:bootstrap
```

4. In GitHub: create the `dev` and `prod` environments (prod with required reviewers and `main` as the only
   allowed branch), set `AWS_DEPLOY_ROLE_ARN` (output `deploy_role_arn`) and `AWS_REGION` on each, and the
   repository variable `DEPLOY_ENABLED=true`. From then on every push to `main` deploys dev and, after
   approval, prod.

Terraform owns the services' shape; CI only moves them to a new task definition revision
(`ignore_changes = [task_definition, desired_count]`), and autoscaling owns the task count.

## Checks without an AWS account

```bash
terraform fmt -check -recursive
terraform init -backend=false && terraform validate
(cd modules/compute && terraform init -backend=false && terraform test)   # also network, data
docker run --rm -v "$PWD:/tf" bridgecrew/checkov:3.2.500 -d /tf --framework terraform --compact --quiet --skip-path /tf/.terraform
```

Not applied against a real account in this assessment: the plan is validated, tested with a mocked provider
and scanned, but resource-level surprises (quotas, name collisions) only show on a real `apply`.
