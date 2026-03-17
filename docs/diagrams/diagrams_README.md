# Technical Diagrams — Microservices v2

Visual representations of the system architecture and flows for the 4-service monorepo.

| Diagram | Description |
|---|---|
| [01-clean-architecture.md](01-clean-architecture.md) | Clean Architecture layers and package structure across all 4 services and shared modules |
| [02-reservation-flow.md](02-reservation-flow.md) | Ticket reservation sequence — happy path, concurrent oversell prevention, and expiry flow |
| [03-state-machine.md](03-state-machine.md) | Reservation and Order state machines with guards, actions, and AuditService integration |
| [04-dynamodb-model.md](04-dynamodb-model.md) | All 6 DynamoDB tables, key prefixes, GSIs, TTL, and access patterns |
| [05-aws-architecture.md](05-aws-architecture.md) | Production AWS infrastructure — ECS Fargate, ALB, DynamoDB, SQS, IAM |
| [06-cicd-pipeline.md](06-cicd-pipeline.md) | GitHub Actions pipeline — multi-service build matrix, Docker push, Terraform apply |
