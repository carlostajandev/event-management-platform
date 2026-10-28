# Prod: two api tasks across two AZs at all times, room to scale for a release, data protected.
environment = "prod"
vpc_cidr    = "10.20.0.0/16"
image_tag   = "bootstrap"

waf_requests_per_5_minutes_per_ip = 1000

api = {
  cpu                    = 1024
  memory                 = 2048
  min_tasks              = 2
  max_tasks              = 20
  requests_per_target    = 3000
  max_in_flight_requests = 64
  rate_limit_user_rps    = 5
}

worker = {
  cpu               = 512
  memory            = 1024
  min_tasks         = 1
  max_tasks         = 10
  backlog_scale_out = 100
}

log_retention_days = 30
container_insights = true
protect_data       = true
github_repository  = "carlostajandev/event-management-platform"
# Set to true if prod has its own AWS account (recommended); false when it shares dev's account.
create_oidc_provider = true
