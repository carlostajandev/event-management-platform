# AWS Production Architecture

## Full Infrastructure Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          AWS Cloud (us-east-1)                                │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     VPC: 10.0.0.0/16                                    │ │
│  │                                                                         │ │
│  │  ┌──────────────────────────── PUBLIC SUBNETS ───────────────────────┐  │ │
│  │  │                                                                   │  │ │
│  │  │  10.0.1.0/24 (AZ-1a)   10.0.2.0/24 (AZ-1b)   10.0.3.0/24 (AZ-1c)│  │ │
│  │  │  ┌─────────────────────────────────────────────────────────────┐  │  │ │
│  │  │  │               Application Load Balancer                    │  │  │ │
│  │  │  │  Port 443 (HTTPS)  ·  TLS 1.3  ·  ACM Certificate         │  │  │ │
│  │  │  │  Port 80 → redirect 443                                    │  │  │ │
│  │  │  │  Health check: GET /actuator/health → 200                  │  │  │ │
│  │  │  └─────────────┬─────────────────────────────────────────────┘  │  │ │
│  │  │                │                                                  │  │ │
│  │  │  ┌─────────────▼──┐   ┌──────────────┐                          │  │ │
│  │  │  │  NAT Gateway   │   │Internet GW   │                          │  │ │
│  │  │  │  (AZ-1a)       │   │              │                          │  │ │
│  │  │  └─────────────┬──┘   └──────────────┘                          │  │ │
│  │  └────────────────┼───────────────────────────────────────────────-─┘  │ │
│  │                   │                                                      │ │
│  │  ┌────────────────┼──────────── PRIVATE SUBNETS ────────────────────┐   │ │
│  │  │                ▼                                                  │   │ │
│  │  │  10.0.10.0/24 (AZ-1a)  10.0.20.0/24 (AZ-1b)  10.0.30.0/24 (1c) │   │ │
│  │  │                                                                   │   │ │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │ │
│  │  │  │ECS Fargate  │  │ECS Fargate  │  │ECS Fargate  │              │   │ │
│  │  │  │Spring Boot 4│  │Spring Boot 4│  │Spring Boot 4│              │   │ │
│  │  │  │Java 21 LTS  │  │Java 21 LTS  │  │Java 21 LTS  │              │   │ │
│  │  │  │512 CPU      │  │512 CPU      │  │512 CPU      │              │   │ │
│  │  │  │1024 MB RAM  │  │1024 MB RAM  │  │1024 MB RAM  │              │   │ │
│  │  │  │port: 8080   │  │port: 8080   │  │port: 8080   │              │   │ │
│  │  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘              │   │ │
│  │  │         └────────────────┼─────────────────┘                     │   │ │
│  │  │                          │                                        │   │ │
│  │  │              ┌───────────┴────────────┐                          │   │ │
│  │  │              │ VPC Endpoints          │                          │   │ │
│  │  │         ┌────┴──────────┐   ┌─────────┴────┐                    │   │ │
│  │  │         │   DynamoDB   │   │     SQS      │                    │   │ │
│  │  │         │ (Gateway EP) │   │ (Interface)  │                    │   │ │
│  │  │         └──────────────┘   └──────────────┘                    │   │ │
│  │  │         ┌──────────────┐   ┌──────────────┐                    │   │ │
│  │  │         │ ECR API (If) │   │CloudWatch(If)│                    │   │ │
│  │  │         └──────────────┘   └──────────────┘                    │   │ │
│  │  └───────────────────────────────────────────────────────────────-─┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
│  ┌───────────────────┐  ┌──────────────────┐  ┌────────────────────────┐    │
│  │     DynamoDB      │  │      SQS         │  │   Secrets Manager      │    │
│  │  emp-events       │  │  purchase-orders │  │  emp/prod/db-password  │    │
│  │  emp-tickets(GSI) │  │  purchase-orders │  │  emp/prod/api-key      │    │
│  │  emp-orders (GSI) │  │  -dlq            │  └────────────────────────┘    │
│  │  emp-idempotency  │  └──────────────────┘                                │
│  │  emp-audit        │  ┌──────────────────┐  ┌────────────────────────┐    │
│  │  emp-shedlock     │  │  CloudWatch Logs │  │   ECR Repository       │    │
│  └───────────────────┘  │  /ecs/emp-prod   │  │  emp:latest            │    │
│                          └──────────────────┘  └────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Security Groups

```
ALB Security Group (emp-prod-alb-sg):
  Inbound:  443 TCP from 0.0.0.0/0 (HTTPS from internet)
            80  TCP from 0.0.0.0/0 (HTTP redirect)
  Outbound: ALL

ECS Tasks Security Group (emp-prod-ecs-sg):
  Inbound:  8080 TCP from ALB SG only (no direct internet access)
  Outbound: ALL (VPC endpoints + NAT for external)

VPC Endpoints Security Group (emp-prod-vpc-endpoints-sg):
  Inbound:  443 TCP from ECS Tasks SG only
  Outbound: (managed by AWS)
```

## IAM Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     IAM Architecture                              │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Task Execution Role (emp-prod-ecs-execution-role)           │ │
│  │  Used by: ECS control plane (not the application)            │ │
│  │                                                              │ │
│  │  · AmazonECSTaskExecutionRolePolicy (managed)                │ │
│  │    → ecr:GetAuthorizationToken                               │ │
│  │    → ecr:BatchGetImage, ecr:GetDownloadUrlForLayer           │ │
│  │    → logs:CreateLogStream, logs:PutLogEvents                 │ │
│  │  · secretsmanager:GetSecretValue                             │ │
│  │    → Resource: arn:aws:secretsmanager:*:*:secret:emp-prod/*  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Task Role (emp-prod-ecs-task-role)                          │ │
│  │  Used by: Spring Boot application at runtime                 │ │
│  │                                                              │ │
│  │  DynamoDB policy:                                            │ │
│  │  · Actions: GetItem, PutItem, UpdateItem, DeleteItem,        │ │
│  │             Query, Scan, BatchWriteItem, DescribeTable        │ │
│  │  · Resources: arn:aws:dynamodb:*:*:table/emp-prod-*          │ │
│  │               arn:aws:dynamodb:*:*:table/emp-prod-*/index/*  │ │
│  │                                                              │ │
│  │  SQS policy:                                                 │ │
│  │  · Actions: SendMessage, ReceiveMessage,                     │ │
│  │             DeleteMessage, GetQueueAttributes                 │ │
│  │  · Resources: arn:aws:sqs:*:*:emp-prod-purchase-orders       │ │
│  │                                                              │ │
│  │  X-Ray policy:                                               │ │
│  │  · Actions: PutTraceSegments, PutTelemetryRecords            │ │
│  │  · Resources: * (X-Ray requires wildcard)                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```
