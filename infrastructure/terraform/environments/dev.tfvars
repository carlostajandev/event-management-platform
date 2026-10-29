# Dev: smallest footprint that still runs both roles. Data can be torn down.
environment = "dev"
vpc_cidr    = "10.10.0.0/16"
image_tag   = "bootstrap" # first image pushed by hand; CI deploys the rest

waf_requests_per_5_minutes_per_ip = 2000

api = {
  cpu                    = 512
  memory                 = 1024
  min_tasks              = 1
  max_tasks              = 2
  requests_per_target    = 3000
  max_in_flight_requests = 64
  rate_limit_user_rps    = 5
}

worker = {
  cpu               = 256
  memory            = 512
  min_tasks         = 1
  max_tasks         = 2
  backlog_scale_out = 100
}

log_retention_days   = 7
container_insights   = false
protect_data         = false
github_repository    = "carlostajandev/event-management-platform"
create_oidc_provider = true
