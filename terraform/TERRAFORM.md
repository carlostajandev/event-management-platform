# Terraform — Event Management Platform

Infraestructura como código para la plataforma de ticketing. Despliega toda la infraestructura AWS usando módulos reutilizables.

## Estructura

```
terraform/
├── main.tf                   ← Wire de todos los módulos
├── variables.tf              ← Variables globales
├── outputs.tf                ← Valores de salida (ALB DNS, tabla names...)
├── versions.tf               ← Constraints de providers
├── modules/
│   ├── networking/           ← VPC, subnets, NAT, VPC endpoints, SGs
│   ├── dynamodb/             ← 6 tablas con GSIs, TTL, PITR, encryption
│   ├── sqs/                  ← Queue + DLQ + CloudWatch alarm
│   ├── ecs/                  ← Cluster, task definition, service, ALB, auto-scaling
│   └── iam/                  ← Roles con mínimo privilegio
└── environments/
    ├── prod.tfvars
    └── dev.tfvars
```

## Decisiones de arquitectura

### Networking
- **VPC privada** — ECS tasks en subnets privadas, solo ALB en pública
- **VPC Endpoints** para DynamoDB, SQS, ECR, CloudWatch — tráfico no sale a internet, reduce costos de NAT Gateway
- **Security Groups** con reglas mínimas — ECS solo acepta tráfico del ALB, no directo desde internet

### DynamoDB
- **PAY_PER_REQUEST** — sin capacity planning, maneja picos de venta de entradas sin throttling
- **PITR habilitado** en prod — restore a cualquier segundo de los últimos 35 días, crítico para datos financieros
- **TTL nativo** en tabla de idempotency — las keys expiran automáticamente sin Lambda ni cron
- **GSI eventId-status-index** en tickets — queries eficientes sin full table scan
- **Encryption at rest** en todas las tablas — AES-256 gestionado por AWS

### SQS
- **DLQ con max_receive_count=3** — mensajes fallidos se mueven a DLQ para investigación
- **Long polling (20s)** — reduce llamadas vacías en ~95%, baja costos
- **CloudWatch alarm** en DLQ — alerta inmediata cuando hay mensajes fallidos
- **KMS encryption** — mensajes de órdenes contienen datos financieros

### ECS
- **Fargate** — sin gestión de instancias EC2, pago por task
- **Rolling deployment 50%/200%** — zero downtime en deploys
- **Health check sobre /actuator/health** — Spring Boot Actuator integrado
- **Auto-scaling** por request count (ALB) Y CPU — reacciona a tráfico real
- **Container Insights** habilitado — métricas detalladas de contenedores

### IAM — Mínimo privilegio
Dos roles separados:

**Task Execution Role** (ECS agent):
- ECR: pull image
- CloudWatch: escribir logs
- Secrets Manager: leer secrets del entorno

**Task Role** (aplicación en runtime):
- DynamoDB: solo las 6 tablas del proyecto, solo acciones necesarias
- SQS: solo la cola purchase-orders
- X-Ray: trazas distribuidas
- Sin wildcards en recursos

## Comandos

```bash
# Inicializar (primera vez)
terraform init

# Ver plan de cambios
terraform plan -var-file=environments/prod.tfvars

# Aplicar
terraform apply -var-file=environments/prod.tfvars

# Destruir (cuidado en prod)
terraform destroy -var-file=environments/dev.tfvars
```

## Estado remoto (producción)

Descomentar en `versions.tf`:
```hcl
backend "s3" {
  bucket         = "nequi-ticketing-tfstate"
  key            = "event-management-platform/terraform.tfstate"
  region         = "us-east-1"
  encrypt        = true
  dynamodb_table = "nequi-ticketing-tfstate-lock"
}
```

El bucket S3 y la tabla DynamoDB para el lock deben crearse manualmente antes del primer `terraform init`.

## Costos estimados (prod, carga moderada)

| Recurso | Config | $/mes |
|---|---|---|
| ECS Fargate | 2 tasks × 0.5vCPU × 1GB | ~$25 |
| DynamoDB | on-demand, 1M writes + 5M reads | ~$8 |
| SQS | 1M mensajes | ~$0.40 |
| ALB | 1 ALB, 10 LCU | ~$20 |
| NAT Gateway | 100GB | ~$14 |
| CloudWatch | Logs 5GB, 10 alarmas | ~$5 |
| VPC Endpoints | Interface endpoints × 4 | ~$30 |
| **Total** | | **~$102/mes** |
