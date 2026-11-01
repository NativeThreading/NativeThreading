package com.github.uright008.nt.core;

import org.bukkit.Server;

/**
 * Leaves platform shell for core.
 *
 * <p>Mirror of {@code NativeThreadingCoreFabric}/{@code NativeThreadingCoreNeoForge}
 * for the leavesclip runtime: boots the shared runtime, exposes {@code /nt} through
 * the Bukkit command map (Paper-family hosts do not hand their Brigadier dispatcher
 * to plugins) and releases worker pools when the plugin is disabled.</p>
 */
public final class NativeThreadingCoreLeaves {

    /** Fallback prefix passed to the Bukkit command map when registering {@code /nt}. */
    static final String COMMAND_PREFIX = "native-threading";

    private NativeThreadingCoreLeaves() {}

    public static void onEnable(Server server) {
        new NativeThreadingCore().onInitialize();
        server.getCommandMap().register(COMMAND_PREFIX, new NtBukkitCommand());
    }

    public static void onDisable() {
        NativeThreadingCore.onServerStopped();
    }
}
