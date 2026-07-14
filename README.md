# room-booking-tools

Admin/support tools for the [room-booking](https://github.com/geoffweatherall/room-booking) project. Unlike [room-booking-api](https://github.com/geoffweatherall/room-booking-api) and [room-booking-webapp](https://github.com/geoffweatherall/room-booking-webapp), nothing here is deployed - each tool is run locally against an already-deployed environment (see the [room-booking project README](https://github.com/geoffweatherall/room-booking#multi-environment-deployments) for how environments work) to help set up, exercise, or maintain that environment.

This checkout expects `room-booking-api` to be a sibling directory - tools authenticate against a deployed environment by reading its Terraform outputs, the same way `room-booking-webapp` does.

## Tools

| Tool | Purpose |
|---|---|
| [sample-data-generator](sample-data-generator/README.md) | Resets a non-production environment and populates it with realistic sample people, rooms, and bookings |
