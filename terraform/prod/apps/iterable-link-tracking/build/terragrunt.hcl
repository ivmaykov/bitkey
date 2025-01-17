include "root" {
  path   = find_in_parent_folders()
  expose = true
}

terraform {
  source = "${get_parent_terragrunt_dir()}//modules/models/iterable-link-tracking"
}

inputs = {
  alias_domain_name  = "links.bitkey.build"
  origin_domain_name = "links.iterable.com"
}

generate "provider_us_east" {
  path      = "provider-useast.tf"
  if_exists = "overwrite"
  contents  = <<eof
provider "aws" {
  default_tags {
    tags = ${jsonencode(include.root.locals.common_tags)}
  }
  region = "us-east-1"
  alias = "us_east"
}
eof
}
