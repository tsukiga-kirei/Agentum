.PHONY: dev-infra down-infra dev-web build-web

dev-infra:
	docker compose -f docker-compose.dev.yml up -d

down-infra:
	docker compose -f docker-compose.dev.yml down

dev-web:
	pnpm --filter @agentum/web dev

build-web:
	pnpm --filter @agentum/web build

