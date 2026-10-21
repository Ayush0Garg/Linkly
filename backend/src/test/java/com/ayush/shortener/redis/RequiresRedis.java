package com.ayush.shortener.redis;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Marks a test class that needs a real redis-server. Without one it is skipped before anything starts (so a missing
 * binary cannot surface as a confusing context-load error), unless LINKLY_REQUIRE_REDIS=true: then it runs and fails
 * loudly, which is what CI sets so the Redis tests can never be skipped by accident.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresRedis.Condition.class)
public @interface RequiresRedis {

    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (RedisTestServer.available() || "true".equalsIgnoreCase(System.getenv("LINKLY_REQUIRE_REDIS"))) {
                return ConditionEvaluationResult.enabled("redis-server available");
            }
            return ConditionEvaluationResult.disabled("redis-server not found: set REDIS_SERVER_BIN or run scripts/dev-redis.sh install");
        }
    }
}
