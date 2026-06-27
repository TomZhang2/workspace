package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks whether a function is shippable to a specific source. A function is
 * shippable when it is present in the source family's {@link BuiltinCatalog}
 * and semantically compatible, OR when it has been registered in the
 * per-server extension whitelist.
 *
 * <p>Extension whitelists allow callers to opt-in additional user-defined
 * functions that the connector advertises as pushable (e.g. UDFs installed on
 * a particular MySQL instance).
 */
public class ShippabilityRegistry {

    private final Map<String, Set<String>> extensionWhitelists = new ConcurrentHashMap<>();

    public boolean isShippable(FunctionSignature sig, String serverId) {
        SourceFamily family = SourceFamily.fromServerId(serverId);
        BuiltinCatalog catalog = family.builtinCatalog();

        if (catalog.contains(sig) && catalog.isSemanticallyCompatible(sig)) {
            return true;
        }

        // Check extension whitelist (per-server, case-insensitive name match)
        Set<String> extensions = extensionWhitelists.get(serverId);
        if (extensions != null && extensions.contains(sig.name().toUpperCase())) {
            return true;
        }

        return false;
    }

    public void registerExtension(String serverId, Set<String> functionNames) {
        // Normalize to uppercase for case-insensitive matching with isShippable.
        Set<String> upper = functionNames.stream()
            .map(String::toUpperCase)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        extensionWhitelists.put(serverId, upper);
    }
}
