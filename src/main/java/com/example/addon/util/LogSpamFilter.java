package com.example.addon.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.RegexFilter;

/**
 * Silences known-harmless vanilla log spam (FakePlayer/Dummy's client-side-only UUID
 * never being registered in the real player-info map, and cape-load noise on join) by
 * installing a Log4j2 filter on the ROOT LoggerConfig at runtime.
 *
 * Not done via a shipped log4j2.xml resource: Fabric loader (verified against
 * fabric-loader 0.18.3's jar -- no such merge logic exists) does NOT auto-merge
 * mod-provided log4j2.xml files with vanilla's, so a second log4j2.xml on the classpath
 * would either be silently ignored or, depending on classpath resource order, replace
 * vanilla's config outright and break normal logging. Mutating the already-running
 * LoggerContext that vanilla set up has neither risk.
 */
public final class LogSpamFilter {
    private LogSpamFilter() {}

    public static void install() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig rootConfig = config.getRootLogger();

            Filter filter = RegexFilter.createFilter(
                "(?s).*(Ignoring player info update for unknown player|Cape [Ll]oaded for player).*",
                null, null, Filter.Result.DENY, Filter.Result.NEUTRAL);
            rootConfig.addFilter(filter);
            ctx.updateLoggers();
        } catch (Exception ignored) {
            // Best-effort cosmetic log filter -- never worth breaking startup over.
        }
    }
}
