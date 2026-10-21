package com.ayush.shortener.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Put on a controller method to apply a named policy before the method runs. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

    /** One of {@link Policies}. */
    String policy();

    /** What the limit is counted against. USER falls back to the client address for anonymous requests. */
    By by() default By.IP;

    enum By { IP, USER }
}
