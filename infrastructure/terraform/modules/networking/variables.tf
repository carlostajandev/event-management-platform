variable "project_name" { type = string }
variable "environment"  { type = string }
variable "vpc_cidr"     { type = string }
<<<<<<< Updated upstream
variable "aws_region"   { type = string; default = "us-east-1" }
=======

variable "aws_region" {
  type    = string
  default = "us-east-1"
}
>>>>>>> Stashed changes
