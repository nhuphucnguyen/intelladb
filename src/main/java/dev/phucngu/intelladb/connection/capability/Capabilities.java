package dev.phucngu.intelladb.connection.capability;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A dialect's optional behaviours, keyed by capability interface — the pattern IntelliJ's
 * database plugin uses for its per-DBMS extensions (a keyed collector with a fallback to the
 * generic implementation) instead of an ever-deeper class hierarchy: a dialect is an assembly
 * of small implementations, and a capability it doesn't bind falls through the chain
 * to {@link #GENERIC}.
 * <p>
 * A dialect overrides {@code DbDialect.capabilities()} with
 * {@code Capabilities.GENERIC.extend().bind(SomeCapability.class, impl)} (or
 * {@code super.capabilities().extend().bind(...)} for a dialect that specializes another one);
 * {@link #find} resolves along the chain, so each dialect states only what it changes.
 */
public final class Capabilities {

    /** The base every dialect extends: the generic SQL behaviour. */
    public static final Capabilities GENERIC = new Capabilities(null);

    static {
        GENERIC.bind(RowMutator.class, GenericRowMutator.INSTANCE);
    }

    private final @Nullable Capabilities fallback;
    private final Map<Class<?>, Object> bindings = new ConcurrentHashMap<>();

    private Capabilities(@Nullable Capabilities fallback) {
        this.fallback = fallback;
    }

    /** A chain element on top of this one: binds shadow this one's, the rest fall through. */
    public @NotNull Capabilities extend() {
        return new Capabilities(this);
    }

    /** Binds {@code implementation} as this chain element's answer for {@code type}. */
    public @NotNull <T> Capabilities bind(@NotNull Class<T> type, @NotNull T implementation) {
        bindings.put(type, implementation);
        return this;
    }

    /** This chain's implementation of {@code type}, falling through to the base; null when nobody binds it. */
    public @Nullable <T> T find(@NotNull Class<T> type) {
        Object bound = bindings.get(type);
        if (bound != null) {
            return type.cast(bound);
        }
        return fallback != null ? fallback.find(type) : null;
    }

    /** {@link #find}, with a caller-supplied answer for when no chain element binds the type. */
    public @NotNull <T> T get(@NotNull Class<T> type, @NotNull T whenUnbound) {
        T found = find(type);
        return found != null ? found : whenUnbound;
    }
}
