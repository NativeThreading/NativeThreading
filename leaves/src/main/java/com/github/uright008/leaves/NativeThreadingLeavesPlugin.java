package com.github.uright008.leaves;

import com.github.uright008.nt.core.NativeThreadingCore;
import com.github.uright008.nt.explosion.ExplosionModule;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Leaves platform shell for the loader-agnostic NativeThreading modules.
 *
 * <p>Everything under {@code com.github.uright008.nt} ships inside this jar but is
 * extracted by leavesclip into {@code plugins/.mixins/native-threading.mixins.jar}
 * and loaded by the server's {@code MixinURLClassLoader}. This shell runs in the
 * plugin class loader and parent-first delegation reaches that same single copy,
 * so config/state/commands are shared with the mixins.</p>
 *
 * <p>Mixins apply when the target classes load, before plugin enable; this shell
 * only wires configuration, commands and the worker-pool lifecycle.</p>
 */
public final class NativeThreadingLeavesPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new NativeThreadingCore().onInitialize();
        new ExplosionModule().onInitialize();
        getServer().getCommandMap().register("native-threading", new NtBukkitCommand());
        getLogger().info("NativeThreading enabled — leavesclip native mixin runtime active");
    }

    @Override
    public void onDisable() {
        NativeThreadingCore.onServerStopped();
        ExplosionModule.onServerStopped();
    }
}
