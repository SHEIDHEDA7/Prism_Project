package dev.prism;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class Gateway {
    private static final ExecutorService PROVIDER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final String OPS_HTML = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Prism Operations</title><style>
            :root{--ink:#211815;--muted:#756862;--paper:#f5f0eb;--card:#fff;--orange:#f45114;--line:#e6ddd7;--green:#2f725c}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:14px system-ui,-apple-system,Segoe UI,sans-serif}.wrap{max-width:1250px;margin:0 auto;padding:30px 22px 50px}.head{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:24px}.brand{font:700 32px Georgia,serif}.sub{color:var(--muted);margin-top:4px}.actions{display:flex;gap:8px;align-items:center}button,select,input{font:inherit;border:1px solid var(--line);border-radius:8px;padding:9px 12px;background:white;color:var(--ink)}button{cursor:pointer}button.primary{background:var(--ink);color:white;border-color:var(--ink)}.badge{border-radius:99px;background:#e4f2ea;color:var(--green);padding:6px 11px;font-size:12px;font-weight:700}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.card{background:var(--card);border:1px solid var(--line);border-radius:13px;padding:17px;box-shadow:0 2px 8px #2118150a}.label{font-size:11px;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);font-weight:700}.value{font-size:26px;font-weight:750;margin-top:8px}.section{margin-top:18px}.section h2{font:700 19px Georgia,serif;margin:0 0 12px}.split{display:grid;grid-template-columns:1fr 2fr;gap:14px}.keyrow{display:flex;justify-content:space-between;border-bottom:1px solid var(--line);padding:10px 0}.keyrow:last-child{border:0}.filters{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}.tablebox{overflow:auto}table{border-collapse:collapse;width:100%;min-width:930px;font-size:12px}th{text-align:left;padding:10px 8px;background:#faf7f4;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.04em}td{padding:10px 8px;border-top:1px solid var(--line);white-space:nowrap}.status{font-weight:700}.ok{color:var(--green)}.bad{color:#b42318}.pagination{display:flex;align-items:center;justify-content:space-between;margin-top:12px;color:var(--muted)}.fine{font-size:11px;color:var(--muted);margin-top:10px}@media(max-width:850px){.grid{grid-template-columns:repeat(2,1fr)}.split{grid-template-columns:1fr}.head{align-items:flex-start;flex-direction:column}}@media(max-width:500px){.grid{grid-template-columns:1fr}.wrap{padding:20px 12px}}
            </style></head><body><main class="wrap"><header class="head"><div><div class="brand">Prism <span style="color:var(--orange)">/</span> Operations</div><div class="sub">Request spend, model activity, tokens and cache performance</div></div><div class="actions"><span id="provider" class="badge">Loading…</span><button id="refresh" class="primary">Refresh data</button></div></header>
            <section class="grid"><article class="card"><div class="label">Requests recorded</div><div id="requestsTotal" class="value">—</div></article><article class="card"><div class="label">Total request cost</div><div id="costTotal" class="value">—</div></article><article class="card"><div class="label">Tokens processed</div><div id="tokensTotal" class="value">—</div></article><article class="card"><div class="label">Semantic cache hit rate</div><div id="cacheRate" class="value">—</div></article></section>
            <section class="split section"><article class="card"><h2>Spend by key</h2><div id="keys"></div></article><article class="card"><h2>Request history</h2><div class="filters"><select id="keyFilter"><option value="">All keys</option></select><input id="modelFilter" placeholder="Model or alias"><select id="statusFilter"><option value="">All statuses</option><option>200</option><option>400</option><option>401</option><option>402</option><option>403</option><option>429</option><option>502</option></select><select id="cacheFilter"><option value="">Cache: all</option><option value="true">Hits</option><option value="false">Misses</option><option value="fallback">Fallbacks</option></select><button id="apply">Apply filters</button></div><div class="tablebox"><table><thead><tr><th>Time</th><th>Key</th><th>Requested / served model</th><th>Provider</th><th>Status</th><th>Tokens in/out</th><th>Cost</th><th>Cache / fallback</th><th>Latency</th><th>Request ID</th></tr></thead><tbody id="rows"></tbody></table></div><div class="pagination"><span id="pageInfo">—</span><div><button id="prev">Previous</button> <button id="next">Next</button></div></div><div class="fine">OpenRouter costs use its reported usage cost when available. Other rows show the pricing fallback or budget estimate source.</div></article></section>
            <script>
            let page=0,pages=0;const $=id=>document.getElementById(id);const fmtCost=n=>n==null?'—':'$'+Number(n).toFixed(6);function textCell(row,value,cls=''){const td=document.createElement('td');td.textContent=value==null?'—':String(value);if(cls)td.className=cls;row.appendChild(td)}
            async function loadSummary(){const [s,h]=await Promise.all([fetch('/ops/api/summary'),fetch('/health')]);const data=await s.json(),health=await h.json(),t=data.totals;$('provider').textContent='Provider: '+health.provider+ (health.openRouterConfigured?' • key configured':'');$('requestsTotal').textContent=Number(t.REQUESTS||0).toLocaleString();$('costTotal').textContent=fmtCost(t.COST);$('tokensTotal').textContent=(Number(t.PROMPT_TOKENS||0)+Number(t.COMPLETION_TOKENS||0)).toLocaleString();$('cacheRate').textContent=(Number(t.CACHE_HIT_RATE||0)*100).toFixed(1)+'%';const keys=$('keys');keys.replaceChildren();const selector=$('keyFilter'),selected=selector.value;selector.replaceChildren(new Option('All keys',''));for(const k of data.byKey){const label=k.KEY_LABEL||'unknown';keys.appendChild((()=>{const d=document.createElement('div');d.className='keyrow';const n=document.createElement('span');n.textContent=label+' · '+k.REQUESTS+' requests';const v=document.createElement('strong');v.textContent=fmtCost(k.COST);d.append(n,v);return d})());selector.add(new Option(label,label));}selector.value=selected}
            async function loadRows(){const params=new URLSearchParams({page,size:25,keyLabel:$('keyFilter').value,model:$('modelFilter').value,status:$('statusFilter').value,cache:$('cacheFilter').value==='fallback'?'':$('cacheFilter').value,fallback:String($('cacheFilter').value==='fallback')});const r=await fetch('/ops/api/requests?'+params);const data=await r.json();pages=data.pages;const body=$('rows');body.replaceChildren();for(const item of data.items){const tr=document.createElement('tr');textCell(tr,item.CREATED_AT?new Date(item.CREATED_AT).toLocaleString():'');textCell(tr,item.KEY_LABEL);textCell(tr,(item.REQUESTED_MODEL||'')+' / '+(item.MODEL||''));textCell(tr,item.PROVIDER);textCell(tr,item.STATUS,item.STATUS===200?'status ok':'status bad');textCell(tr,(item.PROMPT_TOKENS||0)+' / '+(item.COMPLETION_TOKENS||0));textCell(tr,fmtCost(item.COST)+(item.COST_SOURCE&&item.COST_SOURCE!=='openrouter'?' ('+item.COST_SOURCE+')':''));textCell(tr,(item.CACHE_HIT?'HIT':'MISS')+(item.FALLBACK?' / fallback':''));textCell(tr,(item.LATENCY_MS||0)+' ms');textCell(tr,item.UPSTREAM_ID||('#'+item.ID));body.appendChild(tr)}$('pageInfo').textContent='Page '+(data.pages?page+1:0)+' of '+data.pages+' · '+data.total+' requests';$('prev').disabled=page<=0;$('next').disabled=page+1>=pages}
            async function refresh(){try{await loadSummary();await loadRows()}catch(e){$('provider').textContent='Dashboard connection error'}}$('refresh').onclick=refresh;$('apply').onclick=()=>{page=0;loadRows()};$('prev').onclick=()=>{page=Math.max(0,page-1);loadRows()};$('next').onclick=()=>{if(page+1<pages)page++;loadRows()};refresh();setInterval(refresh,15000);
            </script></main></body></html>
            """;
    private final JdbcTemplate db;
    private final OpenRouterProviderAdapter openRouter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object admissionLock = new Object();
    private final Map<String, Key> keys = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> minuteCounts = new ConcurrentHashMap<>();
    private final Map<String, Double> budgetReservations = new ConcurrentHashMap<>();
    private final Map<String, MockProviderAdapter> mockProviders = Map.of("mock-fast", new MockProviderAdapter("mock-fast"), "mock-smart", new MockProviderAdapter("mock-smart"));
    private final Map<String, double[]> pricing = new ConcurrentHashMap<>();
    private final Map<String, double[]> mockPricing = Map.of("mock-fast", new double[]{.15, .60}, "mock-smart", new double[]{2.0, 8.0});
    @Value("${prism.provider.mode:auto}")
    private final String providerMode = "auto";
    @Value("${prism.openrouter.fast-model:openai/gpt-4o-mini}")
    private final String fastModel = "openai/gpt-4o-mini";
    @Value("${prism.openrouter.smart-model:openai/gpt-4o}")
    private final String smartModel = "openai/gpt-4o";
    @Value("${prism.budget.unpriced-reservation-usd:0.02}")
    private final double unpricedReservation = .02;
    @Autowired
    public Gateway(JdbcTemplate db, OpenRouterProviderAdapter openRouter) {
        this.db = db;
        this.openRouter = openRouter;
    }
    Gateway(JdbcTemplate db) {
        this(db, new OpenRouterProviderAdapter());
    }

    @PostConstruct
    void seed() {
        db.execute("CREATE TABLE IF NOT EXISTS prism_key(api_key VARCHAR(100) PRIMARY KEY,label VARCHAR(80),allowed VARCHAR(500),rpm INT,budget DOUBLE)");
        db.execute("ALTER TABLE usage_record ADD COLUMN IF NOT EXISTS key_label VARCHAR(80)");
        db.execute("ALTER TABLE usage_record ADD COLUMN IF NOT EXISTS requested_model VARCHAR(160)");
        db.execute("ALTER TABLE usage_record ADD COLUMN IF NOT EXISTS cost_source VARCHAR(40) DEFAULT 'estimated'");
        db.execute("ALTER TABLE usage_record ADD COLUMN IF NOT EXISTS upstream_id VARCHAR(160)");
        seedKey(new Key("prism-demo-team", "demo-team", Set.of("fast", "smart", "auto", "mock-fast", "mock-smart"), 60, 5));
        seedKey(new Key("prism-demo-student", "demo-student", Set.of("fast", "auto", "mock-fast"), 20, .25));
        db.update("UPDATE usage_record SET key_label=CASE api_key WHEN 'prism-demo-team' THEN 'demo-team' WHEN 'prism-demo-student' THEN 'demo-student' WHEN 'invalid' THEN 'invalid' ELSE 'unknown' END WHERE key_label IS NULL");
        loadPricing();
    }

    private void seedKey(Key k) {
        if (db.queryForList("SELECT api_key FROM prism_key WHERE api_key=?", String.class, k.raw()).isEmpty())
            db.update("INSERT INTO prism_key VALUES(?,?,?,?,?)", k.raw(), k.label(), String.join(",", k.allowed()), k.rpm(), k.monthlyBudget());
        keys.put(k.raw(), k);
    }

    private void loadPricing() {
        Path path = Path.of("data/model_pricing.json");
        if (!Files.exists(path)) return;
        try {
            JsonNode models = mapper.readTree(path.toFile()).path("models");
            models.fields().forEachRemaining(e -> {
                double in = e.getValue().path("input").asDouble(Double.NaN), out = e.getValue().path("output").asDouble(Double.NaN);
                if (Double.isFinite(in) && Double.isFinite(out)) pricing.put(e.getKey(), new double[]{in, out});
            });
        } catch (Exception ignored) {
        }
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "provider", useOpenRouter() ? "openrouter" : "mock", "openRouterConfigured", openRouter.configured());
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chat(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody ChatRequest req) {
        long started = System.nanoTime();
        String key = auth == null ? "" : auth.replaceFirst("(?i)^Bearer\\s+", "");
        Key config = keys.get(key);
        if (config == null) {
            record("invalid", "invalid", req.model(), req.model(), "gateway", 401, 0, 0, 0d, "none", false, false, null, started);
            return error(401, "invalid_api_key", "Unknown virtual key");
        }
        if (req.messages() == null || req.messages().isEmpty()) {
            record(key, config.label(), req.model(), req.model(), "gateway", 400, 0, 0, 0d, "none", false, false, null, started);
            return error(400, "invalid_request", "messages must not be empty");
        }
        String requested = req.model() == null ? "auto" : req.model();
        String prompt = promptText(req.messages());
        String tier = requested.equalsIgnoreCase("auto") ? classify(prompt) : requested;
        if (!Set.of("fast", "smart", "mock-fast", "mock-smart").contains(tier)) {
            record(key, config.label(), requested, tier, "gateway", 400, 0, 0, 0d, "none", false, false, null, started);
            return error(400, "unknown_model", "Use fast, smart, auto, mock-fast, or mock-smart");
        }
        if (!config.allowed().contains(requested) && !config.allowed().contains(tier)) {
            record(key, config.label(), requested, tier, "gateway", 403, 0, 0, 0d, "none", false, false, null, started);
            return error(403, "model_not_allowed", "Model is not allowed for this key");
        }
        boolean remote = useOpenRouter() && !tier.startsWith("mock-");
        String actualModel = remote ? (tier.equals("fast") ? fastModel : smartModel) : (tier.startsWith("mock-") ? tier : tier.equals("fast") ? "mock-fast" : "mock-smart");
        int estimatedPrompt = tokens(prompt);
        double reservation = reservation(actualModel, estimatedPrompt, req.max_tokens() == null ? 512 : req.max_tokens(), remote);
        String denied = admit(key, config, tier, reservation, started);
        if (denied != null) {
            int status = denied.equals("rate_limited") ? 429 : denied.equals("budget_exhausted") ? 402 : 503;
            record(key, config.label(), requested, actualModel, "gateway", status, 0, 0, 0d, "none", false, false, null, started);
            return error(status, denied, denied.equals("rate_limited") ? "Requests per minute limit exceeded" : denied.equals("budget_exhausted") ? "Monthly budget exhausted" : "OpenRouter is selected but OPENROUTER_API_KEY is not configured");
        }

        String normalized = normalize(prompt);
        CacheHit cache = findCache(key, normalized);
        if (cache != null) {
            releaseReservation(key, reservation);
            int pt = estimatedPrompt, ct = cache.completionTokens();
            record(key, config.label(), requested, cache.model(), "cache", 200, pt, ct, 0d, "cache", true, false, null, started);
            if (Boolean.TRUE.equals(req.stream())) return mockStream(cache.answer(), cache.model(), true, 0d);
            return response(cache.answer(), cache.model(), "cache", true, false, 0d, "cache", pt, ct);
        }

        if (remote) {
            if (Boolean.TRUE.equals(req.stream()))
                return openRouterStream(key, config.label(), requested, tier, actualModel, req, reservation, started);
            try {
                OpenRouterProviderAdapter.Result upstream = completeWithRetry(actualModel, req);
                Cost cost = resolveCost(upstream.model(), upstream.promptTokens(), upstream.completionTokens(), upstream.cost(), true, reservation);
                saveCache(key, normalized, upstream.text(), upstream.model(), upstream.promptTokens(), upstream.completionTokens());
                record(key, config.label(), requested, upstream.model(), "openrouter", 200, upstream.promptTokens(), upstream.completionTokens(), cost.amount(), cost.source(), false, false, upstream.id(), started);
                releaseReservation(key, reservation);
                return response(upstream.text(), upstream.model(), "openrouter", false, false, cost.amount(), cost.source(), upstream.promptTokens(), upstream.completionTokens());
            } catch (OpenRouterProviderAdapter.UpstreamException first) {
                if (isTransient(first.status)) try {
                    String fallbackModel = tier.equals("fast") ? smartModel : fastModel;
                    OpenRouterProviderAdapter.Result upstream = completeWithRetry(fallbackModel, req);
                    Cost cost = resolveCost(upstream.model(), upstream.promptTokens(), upstream.completionTokens(), upstream.cost(), true, reservation);
                    saveCache(key, normalized, upstream.text(), upstream.model(), upstream.promptTokens(), upstream.completionTokens());
                    record(key, config.label(), requested, upstream.model(), "openrouter", 200, upstream.promptTokens(), upstream.completionTokens(), cost.amount(), cost.source(), false, true, upstream.id(), started);
                    releaseReservation(key, reservation);
                    return response(upstream.text(), upstream.model(), "openrouter", false, true, cost.amount(), cost.source(), upstream.promptTokens(), upstream.completionTokens());
                } catch (OpenRouterProviderAdapter.UpstreamException fallback) {
                    releaseReservation(key, reservation);
                    record(key, config.label(), requested, actualModel, "openrouter", fallback.status, 0, 0, null, "unknown", false, true, null, started);
                    return error(fallback.status, "provider_error", fallback.getMessage());
                }
                releaseReservation(key, reservation);
                record(key, config.label(), requested, actualModel, "openrouter", first.status, 0, 0, null, "unknown", false, false, null, started);
                return error(first.status, "provider_error", first.getMessage());
            }
        }

        String servedModel = actualModel;
        boolean mockFallback = false;
        String answer;
        try {
            answer = mockWithRetry(servedModel, prompt);
        } catch (RuntimeException primaryFailure) {
            servedModel = servedModel.equals("mock-fast") ? "mock-smart" : "mock-fast";
            mockFallback = true;
            try {
                answer = mockWithRetry(servedModel, prompt);
            } catch (RuntimeException fallbackFailure) {
                releaseReservation(key, reservation);
                record(key, config.label(), requested, actualModel, "mock", 502, 0, 0, null, "unknown", false, true, null, started);
                return error(502, "provider_error", "Both mock providers are unavailable");
            }
        }
        int pt = estimatedPrompt, ct = tokens(answer);
        Cost cost = resolveCost(servedModel, pt, ct, null, false, reservation);
        saveCache(key, normalized, answer, servedModel, pt, ct);
        record(key, config.label(), requested, servedModel, "mock", 200, pt, ct, cost.amount(), cost.source(), false, mockFallback, null, started);
        releaseReservation(key, reservation);
        if (Boolean.TRUE.equals(req.stream())) return mockStream(answer, servedModel, false, cost.amount());
        return response(answer, servedModel, "mock", false, mockFallback, cost.amount(), cost.source(), pt, ct);
    }

    private boolean useOpenRouter() {
        return providerMode.equalsIgnoreCase("openrouter") || (providerMode.equalsIgnoreCase("auto") && openRouter.configured());
    }

    private String admit(String key, Key config, String model, double reservation, long started) {
        synchronized (admissionLock) {
            if (providerMode.equalsIgnoreCase("openrouter") && !openRouter.configured())
                return "provider_not_configured";
            long month = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            Double spent = db.queryForObject("SELECT COALESCE(SUM(cost),0) FROM usage_record WHERE api_key=? AND created_at>=?", Double.class, key, new Timestamp(month));
            if (spent + budgetReservations.getOrDefault(key, 0d) + reservation > config.monthlyBudget())
                return "budget_exhausted";
            String bucket = key + ":" + Instant.now().getEpochSecond() / 60;
            AtomicInteger count = minuteCounts.computeIfAbsent(bucket, x -> new AtomicInteger());
            if (count.incrementAndGet() > config.rpm()) {
                count.decrementAndGet();
                return "rate_limited";
            }
            budgetReservations.merge(key, reservation, Double::sum);
            return null;
        }
    }

    private OpenRouterProviderAdapter.Result completeWithRetry(String model, ChatRequest req) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return openRouter.complete(model, req.messages(), req.max_tokens(), req.temperature());
            } catch (OpenRouterProviderAdapter.UpstreamException e) {
                last = e;
                if (!isTransient(e.status)) throw e;
                if (attempt < 2) backoff(attempt);
            }
        }
        throw last == null ? new OpenRouterProviderAdapter.UpstreamException("OpenRouter unavailable", null) : (OpenRouterProviderAdapter.UpstreamException) last;
    }

    private String mockWithRetry(String provider, String prompt) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return PROVIDER_EXECUTOR.submit(() -> mockProviders.get(provider).generate(prompt)).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = new RuntimeException(e);
                if (attempt < 2) backoff(attempt);
            }
        }
        throw last == null ? new RuntimeException("mock provider unavailable") : last;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(100L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private boolean isTransient(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private ResponseEntity<?> openRouterStream(String key, String label, String requested, String tier, String initialModel, ChatRequest req, double reservation, long started) {
        String model = initialModel;
        boolean fallback = false;
        OpenRouterProviderAdapter.StreamResponse stream;
        try {
            stream = openStreamWithRetry(model, req);
        } catch (OpenRouterProviderAdapter.UpstreamException e) {
            if (!isTransient(e.status)) {
                releaseReservation(key, reservation);
                record(key, label, requested, model, "openrouter", e.status, 0, 0, null, "unknown", false, false, null, started);
                return error(e.status, "provider_error", e.getMessage());
            }
            try {
                model = tier.equals("fast") ? smartModel : fastModel;
                fallback = true;
                stream = openStreamWithRetry(model, req);
            } catch (OpenRouterProviderAdapter.UpstreamException f) {
                releaseReservation(key, reservation);
                record(key, label, requested, model, "openrouter", f.status, 0, 0, null, "unknown", false, true, null, started);
                return error(f.status, "provider_error", f.getMessage());
            }
        }
        final String actualModel = model;
        final boolean didFallback = fallback;
        final OpenRouterProviderAdapter.StreamResponse openedStream = stream;
        double headerEstimate = estimatedCost(actualModel, tokens(promptText(req.messages())), req.max_tokens() == null ? 512 : req.max_tokens());
        SseEmitter emitter = new SseEmitter(120000L);
        String providerId = openedStream.response().headers().firstValue("x-openrouter-generation-id").orElse(null);
        Thread.startVirtualThread(() -> pumpStream(openedStream, emitter, key, label, requested, actualModel, didFallback, providerId, req, reservation, started));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache").header("x-prism-provider", "openrouter").header("x-prism-cache", "MISS").header("x-prism-fallback", Boolean.toString(fallback)).header("x-prism-cost-usd", String.format(Locale.ROOT, "%.8f", headerEstimate)).header("x-prism-cost-source", "estimate").body(emitter);
    }

    private OpenRouterProviderAdapter.StreamResponse openStreamWithRetry(String model, ChatRequest req) {
        OpenRouterProviderAdapter.UpstreamException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return openRouter.openStream(model, req.messages(), req.max_tokens(), req.temperature());
            } catch (OpenRouterProviderAdapter.UpstreamException e) {
                last = e;
                if (!isTransient(e.status)) throw e;
                if (attempt < 2) backoff(attempt);
            }
        }
        throw last;
    }

    private void pumpStream(OpenRouterProviderAdapter.StreamResponse stream, SseEmitter emitter, String key, String label, String requested, String model, boolean fallback, String upstreamId, ChatRequest req, double reservation, long started) {
        StringBuilder answer = new StringBuilder();
        int promptTokens = 0, completionTokens = 0;
        Double providerCost = null;
        String finalModel = model;
        boolean completed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.response().body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    completed = true;
                    continue;
                }
                if (data.isBlank()) continue;
                String streamError = openRouter.streamError(data);
                if (streamError != null) throw new OpenRouterProviderAdapter.UpstreamException(502, streamError);
                OpenRouterProviderAdapter.Result usage = openRouter.parseStreamUsage(data, model);
                if (usage.promptTokens() > 0 || usage.completionTokens() > 0 || usage.cost() != null) {
                    promptTokens = usage.promptTokens();
                    completionTokens = usage.completionTokens();
                    if (usage.cost() != null) providerCost = usage.cost();
                    if (!usage.model().isBlank()) finalModel = usage.model();
                    if (!usage.id().isBlank()) upstreamId = usage.id();
                }
                answer.append(openRouter.streamChunkContent(data));
                emitter.send(SseEmitter.event().data(data));
            }
            if (promptTokens == 0) promptTokens = tokens(promptText(req.messages()));
            if (completionTokens == 0) completionTokens = tokens(answer.toString());
            Cost cost = resolveCost(finalModel, promptTokens, completionTokens, providerCost, true, reservation);
            if (!answer.isEmpty())
                saveCache(key, normalize(promptText(req.messages())), answer.toString(), finalModel, promptTokens, completionTokens);
            record(key, label, requested, finalModel, "openrouter", 200, promptTokens, completionTokens, cost.amount(), cost.source(), false, fallback, upstreamId, started);
            releaseReservation(key, reservation);
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            releaseReservation(key, reservation);
            record(key, label, requested, finalModel, "openrouter", 502, promptTokens, completionTokens, null, "unknown", false, fallback, upstreamId, started);
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("error", Map.of("message", "OpenRouter stream interrupted")))));
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (Exception ignored) {
            }
            emitter.complete();
        }
    }

    private ResponseEntity<?> mockStream(String text, String model, boolean cacheHit, double cost) {
        SseEmitter emitter = new SseEmitter(30000L);
        Thread.startVirtualThread(() -> {
            try {
                for (String piece : text.split("(?<= )")) {
                    Map<String, Object> chunk = Map.of("id", "chatcmpl-prism", "object", "chat.completion.chunk", "created", Instant.now().getEpochSecond(), "model", model, "choices", List.of(Map.of("index", 0, "delta", Map.of("content", piece))));
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(chunk)));
                    Thread.sleep(20);
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("x-prism-provider", cacheHit ? "cache" : "mock").header("x-prism-cache", cacheHit ? "HIT" : "MISS").header("x-prism-fallback", "false").header("x-prism-cost-usd", String.format(Locale.ROOT, "%.8f", cost)).header("x-prism-cost-source", cacheHit ? "cache" : "mock-price").body(emitter);
    }

    private Cost resolveCost(String model, int pt, int ct, Double providerCost, boolean remote, double reservation) {
        if (remote && providerCost != null) return new Cost(providerCost, "openrouter");
        Double estimate = estimatedCostOrNull(model, pt, ct);
        if (estimate != null) return new Cost(estimate, remote ? "local-estimate" : "mock-price");
        return new Cost(reservation, "budget-estimate");
    }

    private double reservation(String model, int promptTokens, int maxTokens, boolean remote) {
        Double estimate = estimatedCostOrNull(model, promptTokens, Math.max(1, maxTokens));
        return estimate == null ? unpricedReservation : Math.max(estimate, .000001);
    }

    private double estimatedCost(String model, int pt, int ct) {
        Double n = estimatedCostOrNull(model, pt, ct);
        return n == null ? unpricedReservation : n;
    }

    private Double estimatedCostOrNull(String model, int pt, int ct) {
        double[] p = pricing.get(model);
        if (p == null) p = mockPricing.get(model);
        return p == null ? null : (pt * p[0] + ct * p[1]) / 1_000_000d;
    }

    double computeCost(String provider, int pt, int ct) {
        double[] p = mockPricing.get(provider);
        return p == null ? 0d : (pt * p[0] + ct * p[1]) / 1_000_000d;
    }

    private void saveCache(String key, String normalized, String answer, String model, int pt, int ct) {
        try {
            db.update("INSERT INTO semantic_cache(api_key,normalized_prompt,answer,model,prompt_tokens,completion_tokens) VALUES(?,?,?,?,?,?)", key, normalized, answer, model, pt, ct);
        } catch (Exception ignored) {
        }
    }

    private CacheHit findCache(String key, String prompt) {
        List<Map<String, Object>> rows = db.queryForList("SELECT normalized_prompt,answer,model,prompt_tokens,completion_tokens FROM semantic_cache WHERE api_key=? ORDER BY id DESC", key);
        for (Map<String, Object> row : rows)
            if (similar(prompt, (String) row.get("NORMALIZED_PROMPT")))
                return new CacheHit((String) row.get("ANSWER"), ((Number) row.get("PROMPT_TOKENS")).intValue(), ((Number) row.get("COMPLETION_TOKENS")).intValue(), (String) row.get("MODEL"));
        return null;
    }

    private boolean similar(String a, String b) {
        Set<String> x = new HashSet<>(Arrays.asList(a.split(" "))), y = new HashSet<>(Arrays.asList(b.split(" ")));
        x.remove("");
        y.remove("");
        if (x.isEmpty() || y.isEmpty()) return false;
        Set<String> z = new HashSet<>(x);
        z.retainAll(y);
        return 2d * z.size() / (x.size() + y.size()) >= .72;
    }

    private String normalize(String s) {
        String clean = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        Map<String, String> synonyms = Map.ofEntries(Map.entry("describe", "explain"), Map.entry("clarify", "explain"), Map.entry("caching", "cache"), Map.entry("cached", "cache"), Map.entry("simply", "simple"), Map.entry("easy", "simple"), Map.entry("beginner", "simple"), Map.entry("terms", "simple"), Map.entry("meaning", "explain"));
        return Arrays.stream(clean.split(" ")).map(t -> synonyms.getOrDefault(t, t)).distinct().sorted().reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
    }

    String classify(String p) {
        String s = p.toLowerCase(Locale.ROOT);
        boolean hard = s.contains("prove") || s.contains("derive") || s.contains("debug") || s.contains("find the bug") || s.contains("why does") || s.contains("edge case") || s.contains("analyze") || s.contains("design") || s.contains("justify") || s.contains("complexity") || s.contains("invariant") || s.contains("lock-free") || s.contains("transaction-safe") || s.contains("phantom-read");
        boolean trivial = p.length() > 160 && (s.contains("hello") || s.contains("thank") || s.contains("capital of") || s.contains("what is 2+2"));
        if (trivial) return "fast";
        hard |= p.length() > 250;
        return hard ? "smart" : "fast";
    }

    private String promptText(List<Map<String, Object>> messages) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> m : messages) {
            Object content = m.get("content");
            if (content instanceof String s) out.append(' ').append(s);
            else if (content != null) out.append(' ').append(content);
        }
        return out.toString().trim();
    }

    private int tokens(String s) {
        return Math.max(1, (int) Math.ceil(s.split("\\s+").length * 1.3));
    }

    private void releaseReservation(String key, double amount) {
        synchronized (admissionLock) {
            budgetReservations.compute(key, (k, v) -> v == null || v <= amount ? null : v - amount);
        }
    }

    private void record(String key, String label, String requested, String model, String provider, int status, int pt, int ct, Double cost, String source, boolean hit, boolean fallback, String upstreamId, long started) {
        db.update("INSERT INTO usage_record(api_key,key_label,requested_model,model,provider,status,prompt_tokens,completion_tokens,cost,cost_source,cache_hit,fallback,latency_ms,upstream_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", key, label, requested, model, provider, status, pt, ct, cost, source, hit, fallback, (System.nanoTime() - started) / 1_000_000, upstreamId);
    }

    private ResponseEntity<?> response(String text, String model, String provider, boolean hit, boolean fallback, double cost, String costSource, int pt, int ct) {
        Map<String, Object> msg = Map.of("role", "assistant", "content", text);
        ChatResponse body = new ChatResponse("chatcmpl-prism", "chat.completion", Instant.now().getEpochSecond(), model, List.of(new Choice(0, msg, "stop")), new Usage(pt, ct, pt + ct));
        return ResponseEntity.ok().header("x-prism-provider", provider).header("x-prism-cache", hit ? "HIT" : "MISS").header("x-prism-fallback", Boolean.toString(fallback)).header("x-prism-cost-usd", String.format(Locale.ROOT, "%.8f", cost)).header("x-prism-cost-source", costSource).body(body);
    }

    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("type", code, "message", message)));
    }

    @GetMapping("/ops/api/summary")
    Map<String, Object> summary() {
        Map<String, Object> total = db.queryForMap("SELECT COUNT(*) requests,COALESCE(SUM(cost),0) cost,COALESCE(SUM(prompt_tokens),0) prompt_tokens,COALESCE(SUM(completion_tokens),0) completion_tokens,COALESCE(AVG(CASE WHEN cache_hit THEN 1.0 ELSE 0.0 END),0) cache_hit_rate,COALESCE(SUM(CASE WHEN fallback THEN 1 ELSE 0 END),0) fallbacks,COALESCE(SUM(CASE WHEN cost_source='budget-estimate' THEN 1 ELSE 0 END),0) budget_estimates FROM usage_record");
        List<Map<String, Object>> byKey = db.queryForList("SELECT key_label,COUNT(*) requests,COALESCE(SUM(cost),0) cost,COALESCE(SUM(prompt_tokens),0) prompt_tokens,COALESCE(SUM(completion_tokens),0) completion_tokens FROM usage_record GROUP BY key_label ORDER BY cost DESC");
        return Map.of("totals", total, "byKey", byKey);
    }

    @GetMapping("/ops/api/requests")
    Map<String, Object> requestPage(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "") String keyLabel, @RequestParam(defaultValue = "") String model, @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String cache, @RequestParam(defaultValue = "false") boolean fallback) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!keyLabel.isBlank()) {
            where.append(" AND key_label=?");
            args.add(keyLabel);
        }
        if (!model.isBlank()) {
            where.append(" AND (model LIKE ? OR requested_model LIKE ?)");
            args.add("%" + model + "%");
            args.add("%" + model + "%");
        }
        if (!status.isBlank()) {
            where.append(" AND status=?");
            args.add(Integer.parseInt(status));
        }
        if (!cache.isBlank()) {
            where.append(" AND cache_hit=?");
            args.add(Boolean.parseBoolean(cache));
        }
        if (fallback) where.append(" AND fallback=TRUE");
        Long total = db.queryForObject("SELECT COUNT(*) FROM usage_record" + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add(safePage * safeSize);
        List<Map<String, Object>> rows = db.queryForList("SELECT id,key_label,requested_model,model,provider,status,prompt_tokens,completion_tokens,cost,cost_source,cache_hit,fallback,latency_ms,upstream_id,created_at FROM usage_record" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", rows, "page", safePage, "size", safeSize, "total", total == null ? 0 : total, "pages", total == null ? 0 : (int) Math.ceil(total / (double) safeSize));
    }

    @GetMapping("/ops/usage")
    Map<String, Object> usage() {
        Map<String, Object> summary = summary();
        Map<String, Object> page = requestPage(0, 50, "", "", "", "", false);
        return Map.of("totals", summary.get("totals"), "byKey", summary.get("byKey"), "recent", page.get("items"), "cacheHitRate", ((Map<?, ?>) summary.get("totals")).get("CACHE_HIT_RATE"));
    }

    @GetMapping("/ops/requests")
    Map<String, Object> requests(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return requestPage(page, size, "", "", "", "", false);
    }

    @PostMapping("/ops/provider/{name}/down")
    Map<String, Object> down(@PathVariable String name, @RequestParam(defaultValue = "true") boolean value) {
        MockProviderAdapter adapter = mockProviders.get(name);
        if (adapter == null) return Map.of("provider", name, "down", false, "error", "unknown mock provider");
        adapter.setDown(value);
        return Map.of("provider", name, "down", value);
    }

    @GetMapping(value = "/ops", produces = MediaType.TEXT_HTML_VALUE)
    String console() {
        return OPS_HTML;
    }

    record Key(String raw, String label, Set<String> allowed, int rpm, double monthlyBudget) {
    }

    record ChatRequest(String model, List<Map<String, Object>> messages, Boolean stream, Integer max_tokens,
                       Double temperature) {
    }

    record CacheHit(String answer, int promptTokens, int completionTokens, String model) {
    }

    record Cost(double amount, String source) {
    }

    record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {
    }

    record Choice(int index, Map<String, Object> message, String finish_reason) {
    }

    record ChatResponse(String id, String object, long created, String model, List<Choice> choices, Usage usage) {
    }
}
