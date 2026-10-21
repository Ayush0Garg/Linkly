# Deploying Linkly: one free Render service, Neon Postgres, Render Key Value

One Docker image holds the API **and** the React app, so the browser sees a single origin: first-party auth cookies, no
CORS. Around it:

```
browser ──https──▶ Render web service (Docker image: Spring Boot + React)   free, 512 MB, 0.1 CPU, sleeps after 15 min idle
                      ├── Neon Postgres        (external, direct connection, TLS)   free
                      └── Render Key Value     (private network, no persistence)     free
```

Files: [Dockerfile](Dockerfile), [render.yaml](render.yaml) (Blueprint), [.dockerignore](.dockerignore),
`backend/src/main/resources/application-prod.yml` (the `prod` profile the image runs with).

## 1. Before you start

| Need | Why |
|---|---|
| The repo on GitHub/GitLab | Render builds from it |
| A [Neon](https://neon.com) account | Postgres |
| A [Render](https://render.com) account | Web service + Key Value |
| (optional) A Google OAuth client id | "Continue with Google". Skip it and email/password sign-in still works |

Make your own **`CODE_ALPHABET`** now. It is a shuffled copy of the 62 letters and digits that decides every generated
short code. The one committed to git is public, and **changing it later changes every code the app generates**, so make it
once and keep it:

```bash
python3 -c "import random,string;a=list(string.ascii_letters+string.digits);random.SystemRandom().shuffle(a);print(''.join(a))"
```

Keep it in a password manager. The production profile refuses to start without it.

## 2. Neon (Postgres)

1. Create a project. Pick the region closest to your Render region: Render `oregon` is AWS `us-west-2`.
2. Open **Connect**. Turn **Connection pooling OFF** and copy the host. It must look like `ep-xxxx-xxxx.us-west-2.aws.neon.tech`
   and must **not** contain `-pooler`. Reason: Flyway (the migration tool) takes a *session* advisory lock, which Neon lists
   as unsupported on pooled (PgBouncer transaction-mode) connections. The app keeps at most 5 connections anyway.
3. Build the three values Render asks for. Neon shows a `postgresql://user:password@host/db?...` URL; the app wants it split:

   | Render variable | Value |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://ep-xxxx-xxxx.us-west-2.aws.neon.tech/neondb?sslmode=require` |
   | `DB_USER` | `neondb_owner` (the role Neon shows) |
   | `DB_PASSWORD` | the password Neon shows |

   Do not copy `channel_binding=require`. TLS is mandatory at Neon and `sslmode=require` covers it.

The migrations (V1 to V5) and the click-partition manager only run plain `CREATE/ALTER/DROP TABLE`, `CREATE INDEX`,
`CREATE SEQUENCE`; there are no extensions and nothing that needs superuser. Checked by booting the production jar against a
fresh database owned by a non-superuser role over TLS: all five migrations applied.

## 3. Render

1. **New → Blueprint**, pick the repo. Render reads `render.yaml` and proposes a web service `linkly` and a Key Value
   instance `linkly-cache`.
2. It asks for the variables marked `sync: false`. Fill in `DB_URL`, `DB_USER`, `DB_PASSWORD`, `CODE_ALPHABET` and (optional)
   `GOOGLE_CLIENT_ID` + `VITE_GOOGLE_CLIENT_ID` (the same value twice, see the table).
3. Apply. The first build takes several minutes (Node build, Maven build). Watch the logs for
   `Started ShortenerApplication` and for `Successfully applied 5 migrations`.

### Environment variables

| Variable | Set by | Value / notes |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | **you**, in the dashboard | Neon, section 2. |
| `CODE_ALPHABET` | **you**, in the dashboard | Your own alphabet, section 1. Never change it. Missing = the app refuses to start. |
| `GOOGLE_CLIENT_ID` | you (optional) | Server side: ID tokens must be issued for this client. Blank = Google sign-in returns 503. |
| `VITE_GOOGLE_CLIENT_ID` | you (optional) | Same id. Baked into the web app **at build time** (Render passes env vars to the Docker build as build args), so changing it needs a redeploy. Blank = no Google button. |
| `JWT_SECRET` | Render generates (`generateValue`) | 256-bit random. Signs sessions; rotating it signs everyone out. |
| `ANALYTICS_SALT` | Render generates | 256-bit random. Mixed into the daily visitor hash. |
| `REDIS_URL` | Render, from the Key Value instance | Private-network address. |
| `REDIS_REQUIRED` | blueprint: `false` | The free Key Value restarts and loses data. The app runs (degraded, and it says so in the log and in `/actuator/health`) without it. |
| `SPA_ENABLED` | blueprint: `true` | Serves the React app from the service. |
| `COOKIE_SECURE` | blueprint: `true` | Render serves HTTPS. |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | blueprint: `health,info` | Keeps `/actuator/prometheus` off the internet. Also set by the `prod` profile and the Dockerfile. |
| `PORT` | Render | The app listens on it (default 10000 on Render, 8080 elsewhere). |
| `RENDER_EXTERNAL_URL` | Render | Used for short links when `APP_BASE_URL` is not set. |
| `APP_BASE_URL` | optional | Only when you attach a custom domain (e.g. `https://sho.rt`). Otherwise short links use `RENDER_EXTERNAL_URL`. |
| `GEOIP_DB` | leave empty | See below. |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | only if the check in 5 fails | See below. |
| `JAVA_OPTS` | optional | Replaces the memory flags in the Dockerfile (a bigger instance). |

Not used on Render: `SWAGGER_ENABLED` (Swagger stays at `/swagger-ui/index.html`; set `false` to switch the docs off).

**Country data (`GEOIP_DB`)** is left empty for the first deploy: the dashboard then shows no countries. The offline
database (DB-IP Lite) is licensed CC BY 4.0, which requires attribution (the app's footer already links db-ip.com) and a
download at build or start time. Adding it later is a separate step: put the `.mmdb` file in the image, set `GEOIP_DB` to its path.

## 4. Google sign-in (optional)

In Google Cloud Console → Credentials → your OAuth client (type *Web application*): under **Authorized JavaScript origins**
add `https://<your-service>.onrender.com` (and your custom domain if you add one). The sign-in button uses Google Identity
Services, which needs no redirect URIs. It can take a few minutes for Google to accept a new origin.

## 5. First-deploy verification

Replace `$APP` with `https://<your-service>.onrender.com`.

- [ ] `curl -s $APP/actuator/health/liveness` → `{"status":"UP"}`
- [ ] `curl -s -o /dev/null -w "%{http_code}\n" $APP/actuator/prometheus` → `404`, and `$APP/actuator/health` → `401` (only the two probes are public, so strangers cannot wake the database)
- [ ] Open `$APP/` → the Linkly sign-in page. Reload on `$APP/links` → still the app, not an error
- [ ] Register an account, create a link, copy the short URL: it must start with `$APP/linkly-`
- [ ] Open the short URL in a private window → lands on the destination; the dashboard shows the click within a couple of seconds
- [ ] `curl -s -o /dev/null -w "%{http_code}\n" -H 'Accept: application/json' $APP/api/nope` → `401` signed out (a signed-in `404` is JSON, never HTML)
- [ ] Logs show `Redis connected` (not the "REDIS IS OFF" banner), no `ERROR`
- [ ] With Google: the button appears on the sign-in page and signing in works
- [ ] **Client addresses are real (below)**

### Rate limits must count each visitor, not "Render"

Limits are counted per client address. The app takes the address from `X-Forwarded-For` **only** when the connection comes
from a private or loopback address (so a visitor cannot invent their own). I did not assume what Render's front door
looks like, so check it:

1. From your laptop, fail a sign-in 12 times (the limit is 10 per minute per address; Redis is on, so it is shared):
   ```bash
   J=$(mktemp); curl -s -c $J -o /dev/null $APP/api/auth/csrf; X=$(awk '/XSRF/{print $7}' $J)
   for i in $(seq 1 12); do
     curl -s -b $J -o /dev/null -w "%{http_code} " -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' \
       -d "{\"email\":\"nobody$i@example.com\",\"password\":\"wrong password\"}" $APP/api/auth/login
   done; echo
   ```
   Expect `401` ten times, then `429`.
2. Straight after, from **another network** (your phone on mobile data, Wi-Fi off), try one sign-in on the web page.
   - Wrong password message (401) → correct: each visitor has their own counter.
   - "Too many attempts" (429) → **wrong**: every visitor is being counted as one address (Render's proxy is not in the
     private ranges, so its header is ignored). Fix: find the proxy's address (set `LOGGING_LEVEL_ORG_APACHE_CATALINA_VALVES_REMOTEIPVALVE=DEBUG`;
     the valve logs the original remote address) and set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regex that matches
     only it, e.g. `203\.0\.113\.\d+`. Never `.*`: anyone could then choose their own address and dodge every limit.

## 6. Honest limits of this setup

- **Cold start is long.** Render spins a free service down after 15 minutes without traffic; a request wakes it. Render
  says "about a minute" for a typical app. This is a JVM app on 0.1 CPU. I measured the production jar with a 0.1 CPU /
  512 MB cap (cgroup quota; Render's own scheduling may differ): **ready after about 221 s (3.7 min)** (Spring reports 192 s),
  against 15 s on an unrestricted core. Candidate fixes measured the same way: AppCDS 167 s, AppCDS + Swagger off 163 s,
  Swagger off 190 s, lazy initialisation 195 s; none reaches 2 minutes. Until you decide, expect minutes, not seconds, for
  the first request after idle, **including short links** (during the wait Render shows its own waking page). A free
  instance is a demo, not a redirect service.
- **Neon scales to zero** after 5 minutes without queries on the free plan. The first query after that waits for the
  database to wake (Neon documents a few hundred milliseconds; the app waits up to 30 s per connection and 60 s at startup
  to be safe). The pool keeps no idle connections (`minimum-idle: 0`, closed after 30 s) so the database can sleep.
- **Key Value loses everything when it restarts** (free plan, memory only). Cached links and rate-limit counters come back
  by themselves. **Clicks that are in the Redis queue at that moment are lost** (normally seconds' worth: the consumer
  stores about 20,000 clicks/s). If Redis is away the app keeps clicks in memory and says so, but a restart of the web
  service then also drops those (up to about a second).
- **Redis timeouts under a starved CPU.** Commands time out after 100 ms (kept on purpose: a slow Redis must never slow a
  redirect). At 0.1 CPU the app may occasionally see 5 in a row and switch to degraded mode for a few seconds, then recover.
  Nothing is lost when it happens (load test: no lost clicks, duplicates detected).
- **750 free instance hours per month** per workspace on Render; **free Render Postgres is not used** (it expires after 30 days).
- Nothing is persisted on the web service's disk. Logs are Render's.
- **No keep-alive pinger (a choice, not a rule).** The first request after idle simply waits for the boot. If that gets
  annoying later, an *external* pinger (UptimeRobot, cron-job.org, a scheduled GitHub Action) calling
  `/actuator/health/liveness` every 10 minutes would keep the service up. That path never touches the database, so Neon
  can still sleep. Things to know first: one always-on free service uses about 744 of the workspace's 750 free hours a month
  (so it must be the only free service there); Render's docs say nothing for or against keep-alive pings; and Render may
  still restart a free service at any time, and every deploy restarts it, so a long boot will still happen occasionally. A
  frontend cannot do this job: Vercel's free cron runs once a day, and browser polling only runs while someone is on the page.
- The numbers in README "Performance" were measured on a 4-core desktop, not on this tier.

## 7. Hosting the frontend somewhere else (Vercel, Netlify, Cloudflare Pages)

Possible, but not free of work, and not worth it here. The app relies on being one origin:

- Auth is HttpOnly cookies with `SameSite=Lax` and a double-submit CSRF cookie that the browser script reads. On a
  different site (`*.vercel.app` talking to `*.onrender.com`) the cookies are third-party: they would need `SameSite=None; Secure`,
  CORS with credentials, and a way to hand the CSRF token to a script that cannot read the API's cookie. And browsers
  (Safari, Brave, Chrome's phase-out) increasingly block third-party cookies, so it would break unpredictably.
  `vercel.app`, `netlify.app` and `onrender.com` are on the Public Suffix List, so they are *different sites* even though
  they look related.
- **Works properly only on one registrable domain you own**: `app.example.com` (frontend) and `api.example.com` (Render,
  custom domains are free). Then cookies are same-site. Code changes needed: CORS config on the API, cookie `Domain=example.com`
  for the session and CSRF cookies, a base-URL setting in the frontend client (it uses relative `/api` today), and a CSP
  `connect-src` for the API host.
- **Proxy instead**: the frontend host forwards `/api/*` to Render (Vercel/Netlify rewrites). Cookies are then first-party,
  but the API sees the proxy's address, not the visitor's, so per-address rate limits (sign-in brute-force protection)
  would count everyone together unless the proxy's address range is trusted, and these hosts do not publish fixed ranges.
- What you would gain: the page shell loads instantly while the API sleeps. But every page needs the API, so the visitor still
  waits for the wake-up. Keep the single service.

## 8. Moving to Oracle Cloud (later)

Nothing here blocks it:

- The same image runs anywhere (`docker run -p 8080:8080 --env-file prod.env linkly`). Put Caddy or nginx in front for HTTPS
  (same host or a Docker network = private address, so the default forwarded-header trust works without
  `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`).
- Carries over unchanged: every environment variable above (use the **same** `CODE_ALPHABET` and `JWT_SECRET`), the migrations,
  the `prod` profile, the health paths, the rate limits.
- Changes: run Postgres and Redis on the VM (or keep Neon); give Redis `appendonly yes` so queued clicks survive restarts;
  set `REDIS_REQUIRED=true`; raise `JAVA_OPTS` (more heap) since the VM is not capped at 512 MB; no sleep, so the cold-start
  and Neon-wake concerns go away (with a local Postgres); add backups; `APP_BASE_URL` = your domain.
