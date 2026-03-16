# Terraform — Event Management Platform

Infrastructure as code for the ticketing platform. Deploys all AWS infrastructure using reusable modules.

## Structure
```
terraform/
├── main.tf                   ← Wires all modules together
├── variables.tf              ← Global variables
├── outputs.tf                ← Output values (ALB DNS, table names...)
├── versions.tf               ← Provider constraints
├── modules/
│   ├── networking/           ← VPC, subnets, NAT, VPC endpoints, SGs
│   ├── dynamodb/             ← 6 tables with GSIs, TTL, PITR, encryption
│   ├── sqs/                  ← Queue + DLQ + CloudWatch alarm
│   ├── ecs/                  ← Cluster, task definition, service, ALB, auto-scaling
│   └── iam/                  ← Least-privilege roles
└── environments/
    ├── prod.tfvars
    └── dev.tfvars
```

## Architecture Decisions

### Networking
- **Private VPC** — ECS tasks in private subnets, only ALB in public subnet
- **VPC Endpoints** for DynamoDB, SQS, ECR, CloudWatch — traffic never leaves to the internet, reduces NAT Gateway costs
- **Security Groups** with minimal rules — ECS only accepts traffic from the ALB, not directly from the internet

### DynamoDB
- **PAY_PER_REQUEST** — no capacity planning, handles ticket sale peaks without throttling
- **PITR enabled** in prod — restore to any second in the last 35 days, critical for financial data
- **Native TTL** on the idempotency table — keys expire automatically without Lambda or cron
- **GSI eventId-status-index** on tickets — efficient queries without full table scan
- **Encryption at rest** on all tables — AES-256 managed by AWS

### SQS
- **DLQ with max_receive_count=3** — failed messages move to DLQ for investigation
- **Long polling (20s)** — reduces empty calls by ~95%, lowers costs
- **CloudWatch alarm** on DLQ — immediate alert when there are failed messages
- **KMS encryption** — order messages contain financial data

### ECS
- **Fargate** — no EC2 instance management, pay per task
- **Rolling deployment 50%/200%** — zero downtime deploys
- **Health check on /actuator/health** — integrated Spring Boot Actuator
- **Auto-scaling** by request count (ALB) AND CPU — reacts to real traffic
- **Container Insights** enabled — detailed container metrics

### IAM — Least Privilege
Two separate roles:

**Task Execution Role** (ECS agent):
- ECR: pull image
- CloudWatch: write logs
- Secrets Manager: read environment secrets

**Task Role** (application at runtime):
- DynamoDB: only the 6 project tables, only required actions
- SQS: only the purchase-orders queue
- X-Ray: distributed tracing
- No wildcards on resources

## Commands
```bash
# Initialize (first time)
terraform init

# Preview changes
terraform plan -var-file=environments/prod.tfvars

# Apply
terraform apply -var-file=environments/prod.tfvars

# Destroy (careful in prod)
terraform destroy -var-file=environments/dev.tfvars
```

## Remote State (Production)

Uncomment in `versions.tf`:
```hcl
backend "s3" {
  bucket         = "nequi-ticketing-tfstate"
  key            = "event-management-platform/terraform.tfstate"
  region         = "us-east-1"
  encrypt        = true
  dynamodb_table = "nequi-ticketing-tfstate-lock"
}
```

The S3 bucket and DynamoDB table for the lock must be created manually before the first `terraform init`.

## Estimated Costs (prod, moderate load)

| Resource | Config | $/month |
|---|---|---|
| ECS Fargate | 2 tasks × 0.5vCPU × 1GB | ~$25 |
| DynamoDB | on-demand, 1M writes + 5M reads | ~$8 |
| SQS | 1M messages | ~$0.40 |
| ALB | 1 ALB, 10 LCU | ~$20 |
| NAT Gateway | 100GB | ~$14 |
| CloudWatch | 5GB logs, 10 alarms | ~$5 |
| VPC Endpoints | Interface endpoints × 4 | ~$30 |
| **Total** | | **~$102/month** |