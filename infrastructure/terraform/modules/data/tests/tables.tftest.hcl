mock_provider "aws" {}

run "dev_can_be_torn_down" {
  command = plan

  variables {
    name         = "ticketing-dev"
    protect_data = false
  }

  assert {
    condition     = length(aws_dynamodb_table.this) == 5
    error_message = "The five tables of the local schema must exist."
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : !table.deletion_protection_enabled])
    error_message = "Dev tables must be deletable."
  }
}

run "prod_tables_are_protected" {
  command = plan

  variables {
    name         = "ticketing-prod"
    protect_data = true
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.deletion_protection_enabled && table.point_in_time_recovery[0].enabled])
    error_message = "Prod tables need deletion protection and point-in-time recovery."
  }
}

run "only_short_lived_tables_expire" {
  command = plan

  variables {
    name         = "ticketing-dev"
    protect_data = false
  }

  assert {
    condition     = toset([for key, table in aws_dynamodb_table.this : key if length(table.ttl) > 0]) == toset(["idempotency", "outbox"])
    error_message = "Only idempotency keys and published outbox entries expire; the audit trail is kept."
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.billing_mode == "PAY_PER_REQUEST"])
    error_message = "Flash sales are spikes: tables are on-demand."
  }
}
