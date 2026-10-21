# syntax=docker/dockerfile:1
#
# One image: the Spring Boot API with the React app inside it (same origin => first-party cookies, no CORS).
# Build context is the repository root:   docker build -t linkly .
# Optional:  --build-arg VITE_GOOGLE_CLIENT_ID=<oauth client id>   (Google sign-in button; public value, not a secret)

# ---- 1. build the React app ------------------------------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ARG VITE_GOOGLE_CLIENT_ID=""
ENV VITE_GOOGLE_CLIENT_ID=${VITE_GOOGLE_CLIENT_ID}
RUN npm run build

# ---- 2. build the jar, with the built app inside it -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS jar
WORKDIR /build
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY backend/src src
# The built app goes into the jar's static resources here, at build time only; it is never committed to src/.
COPY --from=web /web/dist/ src/main/resources/static/
# -Dmaven.test.skip: tests need a database and Redis and run in CI, not here.
# -Dspring-boot.excludeGroupIds: the dev-only embedded Postgres (~80 MB of binaries for every OS) stays out of the jar.
RUN ./mvnw -q -B -Dmaven.test.skip=true -Dspring-boot.excludeGroupIds=io.zonky.test,io.zonky.test.postgres package \
 && mv target/shortener-*.jar /build/app.jar

# ---- 3. run it ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home linkly
WORKDIR /app
COPY --from=jar /build/app.jar app.jar
USER linkly

# Memory budget for Render's free 512 MB container. Measured on the real jar after 3,000 redirects: about 350 MB
# resident (heap ~120 MB committed, metaspace ~100 MB, symbols ~45 MB, code ~25 MB, 40 threads). Every area is capped
# so a surge cannot add up to more than the container has:
#   -XX:+UseSerialGC            one GC thread, no parallel GC worker threads or their memory; right for 1 vCPU and less
#   -Xms48m -Xmx192m            heap grows only when needed; the live data set is ~70 MB, so 192 MB leaves room for bursts
#   -XX:MaxMetaspaceSize=144m   class metadata (Hibernate, Spring, Tomcat); ~100 MB in use, so a leak fails here with
#                               a clear error instead of getting the container killed
#   -XX:ReservedCodeCacheSize=48m, MaxDirectMemorySize=48m   JIT code and network buffers (Lettuce, Tomcat)
#   -Xss512k                    thread stacks; the JVM default of 1 MB per thread is not needed here
#   -XX:TieredStopAtLevel=1     C1 compiler only: far less CPU spent compiling at startup (0.1 vCPU is the bottleneck),
#                               at the cost of some peak speed, which this tier of hardware never reaches anyway
#   -XX:+ExitOnOutOfMemoryError die and be restarted by the platform rather than limp along half-working
#   -XX:-UsePerfData            no jstat memory-mapped file
# Override the whole string with the JAVA_OPTS environment variable (e.g. on a bigger instance).
ENV JAVA_OPTS="-XX:+UseSerialGC -Xms48m -Xmx192m -XX:MaxMetaspaceSize=144m -XX:ReservedCodeCacheSize=48m \
-XX:MaxDirectMemorySize=48m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData"

# Production behaviour: the "prod" profile (small DB pool, no /actuator/prometheus) and the web app served from here.
# The server listens on $PORT (Render sets it; 8080 when unset).
ENV SPRING_PROFILES_ACTIVE=prod \
    SPA_ENABLED=true \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info
EXPOSE 8080
# exec: java becomes PID 1 and receives SIGTERM, so the app shuts down gracefully (drains the click queue) on deploy
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
