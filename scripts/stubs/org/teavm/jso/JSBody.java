package org.teavm.jso;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only stand-in used by the offline JVM checks; the TeaVM build supplies the real annotation. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface JSBody{
    String[] params() default {};

    String script();
}
