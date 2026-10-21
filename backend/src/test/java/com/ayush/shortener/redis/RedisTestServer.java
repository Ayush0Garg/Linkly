package com.ayush.shortener.redis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

/**
 * A real redis-server on a random port, shared by the tests in one JVM, that tests can stop and start again to
 * simulate an outage. Mocks would hide exactly the bugs that matter here (TTLs, Lua, streams, timeouts).
 *
 * The binary is found via REDIS_SERVER_BIN, then PATH, then the folder scripts/dev-redis.sh installs into.
 * Without one the Redis tests are skipped, unless LINKLY_REQUIRE_REDIS=true (set that in CI) in which case they fail.
 */
public final class RedisTestServer {

    private static RedisTestServer shared;

    private final String binary;
    private final int port;
    private final Path dataDir;
    private Process process;

    private RedisTestServer(String binary) throws IOException {
        this.binary = binary;
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.dataDir = Files.createTempDirectory("linkly-redis-test");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    /** Whether a redis-server binary can be found at all. */
    public static boolean available() {
        return findBinary() != null;
    }

    /** The shared server, started on first use. Skips (or fails, see above) the calling test if Redis is unavailable. */
    public static synchronized RedisTestServer get() {
        if (shared == null) {
            String binary = findBinary();
            if (binary == null) {
                String message = "redis-server not found: set REDIS_SERVER_BIN or run scripts/dev-redis.sh install";
                if ("true".equalsIgnoreCase(System.getenv("LINKLY_REQUIRE_REDIS"))) {
                    throw new IllegalStateException(message);
                }
                Assumptions.abort(message);
            }
            try {
                shared = new RedisTestServer(binary);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        shared.ensureRunning();
        return shared;
    }

    private static String findBinary() {
        String fromEnv = System.getenv("REDIS_SERVER_BIN");
        if (fromEnv != null && Files.isExecutable(Path.of(fromEnv))) {
            return fromEnv;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(dir, "redis-server");
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        Path installed = Path.of(System.getProperty("user.home"), ".local/share/linkly/redis-env/bin/redis-server");
        return Files.isExecutable(installed) ? installed.toString() : null;
    }

    private static final java.util.concurrent.atomic.AtomicInteger PREFIXES = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A key prefix no other test context uses. Spring caches application contexts across test classes, so several live
     * contexts (each with its own database and its own background consumers) can share this one Redis; without separate
     * prefixes they would read each other's streams.
     */
    public static String uniquePrefix() {
        return "t" + PREFIXES.incrementAndGet() + "-" + System.nanoTime() + ":";
    }

    public String url() {
        return "redis://localhost:" + port;
    }

    public int port() {
        return port;
    }

    public synchronized void ensureRunning() {
        if (process != null && process.isAlive()) {
            signal("CONT"); // harmless if it was never paused
            return;
        }
        try {
            process = new ProcessBuilder(List.of(binary, "--port", String.valueOf(port), "--bind", "127.0.0.1",
                    "--save", "", "--appendonly", "no", "--dir", dataDir.toString()))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start redis-server", e);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IllegalStateException("redis-server did not start on port " + port);
    }

    /**
     * Freezes the server without killing it: connections stay open and every command hangs, but the data survives.
     * That is what a network partition looks like from the application, which a kill-and-restart cannot reproduce.
     */
    public synchronized void pause() {
        signal("STOP");
    }

    public synchronized void resume() {
        signal("CONT");
    }

    private void signal(String name) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            new ProcessBuilder("kill", "-" + name, String.valueOf(process.pid())).start().waitFor();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Kills the server, as an outage would: connections drop and the port stops answering. */
    public synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A closed port on this machine, for "configured but unreachable" tests. */
    public static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * With the server paused, keeps issuing the given Redis-touching action until the gateway notices and leaves UP.
     * A command timeout is only one strike now (see {@code RedisGateway.TIMEOUT_STRIKES}), so tests that simulate a
     * partition need a few of them, as real traffic would produce.
     */
    public static void untilGatewayNotices(com.ayush.shortener.redis.RedisGateway gateway, Runnable touchRedis) {
        for (int i = 0; i < 50 && gateway.mode() == com.ayush.shortener.redis.RedisGateway.Mode.UP; i++) {
            touchRedis.run();
        }
        if (gateway.mode() == com.ayush.shortener.redis.RedisGateway.Mode.UP) {
            throw new AssertionError("the gateway never noticed that Redis stopped answering");
        }
    }
}
