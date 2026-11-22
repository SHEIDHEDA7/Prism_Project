# Prism LLM Gateway and Semantic Cache

Prism is a runnable Java Spring Boot LLM gateway. It exposes one OpenAI-compatible chat endpoint, authenticates virtual keys, routes requests to configurable OpenRouter fast/smart models, caches repeated meaning, and records each request's cost and token usage in a local database. Mock mode remains available for tests, offline demos, and users who do not yet have an OpenRouter key.

## Run it

Requirements: Java 21 or newer and Maven 3.9 or newer.

```sh
mvn spring-boot:run
```

Open [http://localhost:8080/ops](http://localhost:8080/ops). The H2 database is created under `data/` and survives restarts. Stop the server with Ctrl+C.

### Use OpenRouter

Create an OpenRouter key in your account, copy `.env.example` to `.env`, and add your key and preferred model IDs. `.env` is ignored by Git. Do not paste the key into source code or share it in chat.

```sh
cp .env.example .env
# Edit .env and set OPENROUTER_API_KEY
mvn spring-boot:run
```

The default provider mode is `auto`: a configured key turns on OpenRouter; without a key, the mock providers run locally. Set `PRISM_PROVIDER_MODE=mock` to force mocks or `PRISM_PROVIDER_MODE=openrouter` to require OpenRouter. Fast and smart model IDs can be changed with the environment variables above or in `application.yml`.

OpenRouter returns actual token use and request cost in the completion `usage` object. Prism records the reported `usage.cost` for each request when present. If cost is absent, Prism reads input/output prices from `data/model_pricing.json`; when neither source has a price, it records a configurable `$0.02` budget estimate and marks its source in the dashboard. Add model-specific rates to that JSON before relying on strict budget enforcement. OpenRouter's API uses a Bearer key and the OpenAI-compatible `/api/v1/chat/completions` route; see the [official chat completion API](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request?explorer=true) and [usage accounting notes](https://openrouter.ai/blog/announcements/smarter-charts-inline-svgs-and-live-usage-accounting/).

Demo key: `prism-demo-team` (fast, smart, auto; 60 requests/minute; $5/month). Restricted key: `prism-demo-student` (fast and auto; 20 requests/minute; $0.25/month). These are public demo credentials and must be replaced before deployment.

```sh
curl http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer prism-demo-team' -H 'Content-Type: application/json' \
  -d '{"model":"auto","messages":[{"role":"user","content":"Prove that the square root of 2 is irrational."}]}'
```

Use `"stream":true` for upstream SSE pass-through followed by `data: [DONE]`. OpenRouter streaming requests ask for final usage so the completed stream can be metered and saved. The response includes `x-prism-provider`, `x-prism-cache`, `x-prism-fallback`, `x-prism-cost-usd`, and `x-prism-cost-source`. Try another phrasing such as “Describe semantic cache in simple terms” to observe a cache hit. Cache entries are scoped by virtual key.

## API

* `POST /v1/chat/completions`: OpenAI-compatible request fields `model`, `messages`, `stream`, `max_tokens`; accepts model names `fast`, `smart`, `auto`, `mock-fast`, `mock-smart`.
* `GET /ops`: live operations dashboard with total spend, requests, tokens, cache rate, spend by key, and paginated/filterable request history. It refreshes automatically and never displays virtual-key secrets.
* `GET /ops/api/summary`: dashboard totals and per-key totals.
* `GET /ops/api/requests?page=0&size=25`: paginated request history with optional `keyLabel`, `model`, `status`, `cache`, and `fallback` filters.
* `GET /ops/usage`: compatibility usage summary API.
* `GET /ops/requests?page=0&size=100`: paginated request records.
* `POST /ops/provider/mock-fast/down?value=true|false`: simulate and restore the mock fast provider.
* `GET /health`: liveness check.

Errors use `{ "error": { "type": ..., "message": ... } }`. Invalid keys return 401, disallowed models 403, budget exhaustion 402, and rate limiting 429.

## Architecture

`Gateway` handles authentication, per-key model access, RPM/month budget admission, semantic cache lookup, auto routing, cost metering, request logs and the dashboard API. `OpenRouterProviderAdapter` makes OpenAI-compatible HTTP calls with bounded timeouts, retries transient failures and provides a true streaming response path. `MockProviderAdapter` supports local demos and automated tests. H2 file storage persists virtual keys, cache entries and request history. The dashboard paginates all recorded requests and supports filters for key, model, status, cache and fallback.

The `auto` router applies interpretable prompt rules, including short hard prompts and long trivial prompts. `scripts/routing_eval.py` compares the same rules against a length-only baseline on labeled traps. OpenRouter calls use a configurable timeout, retry transient failures up to three attempts with exponential delays, then fall back to the other configured model tier. Mock mode also supports simulated provider outage tests.

The semantic cache uses normalized text, a small synonym map, and Jaccard token similarity (0.72 threshold). This is a local deterministic semantic approximation, not a neural embedding model. It avoids paid APIs and is easy to inspect, but can miss paraphrases with little vocabulary overlap or match unrelated short prompts. A production version should use an embedding adapter and a vector database, with tenant IDs in every lookup.

Mock pricing seed values in `data/model_pricing.json` are examples. Add input/output rates for your selected OpenRouter model slugs there to enable a local cost fallback and a better in-flight budget reservation. The API key is only read from `OPENROUTER_API_KEY`.

## Verify

```sh
mvn test
python3 scripts/routing_eval.py
# In a second terminal while the server is running:
python3 scripts/smoke_test.py
python3 scripts/load_test.py
```

The smoke test checks API shape, metering headers, auth, allowlists, cache and SSE. Load test submits 100 concurrent calls against a 60 RPM key and reports admissions, rate-limit rejections, unexpected responses and over-admission. The routing evaluator prints each case plus accuracy and baseline. Tests use mocks and do not make paid OpenRouter calls.

## Design limits to understand

This is a capstone demo, not a hardened production deployment. Admission locks and RPM counters are process-local, so run one instance only; horizontally scaled deployments need shared atomic state. Monthly budget admission reserves using local model prices where available and a configurable conservative estimate otherwise. The ops dashboard currently has no administrative login and is intended for local use; put it behind authentication before exposing the app publicly. Semantic cache matching uses normalized text and token similarity rather than embeddings. OpenRouter integration is real, but it has not been live-tested without the user's API key. Use HTTPS, secret rotation, admin auth, database migrations, monitoring and a vector cache before production.

## Walkthrough

1. Start the app in mock mode, or set an OpenRouter key and model IDs first. Open `/ops`.
2. Send a normal `fast` request and inspect exact OpenRouter spend or the mock estimate in the request history.
3. Send a hard short `auto` request and inspect the smart route; run the routing evaluation.
4. Repeat a similar question under the same key and see `x-prism-cache: HIT`; use another key to confirm tenant isolation.
5. Try `smart` with `prism-demo-student` and inspect the allowlist error.
6. Use the console to take down `mock-fast`, send a `fast` request, then restore the provider; the fallback header records the event.
7. Set `stream:true` and observe the SSE chunks and terminator.

## Verification report

Run the verification commands above in the target environment. Results from this workspace run are recorded in `VERIFICATION.md` after execution.
