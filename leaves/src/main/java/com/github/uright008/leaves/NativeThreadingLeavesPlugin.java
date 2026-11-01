package com.github.uright008.leaves;

import com.github.uright008.nt.core.NativeThreadingCoreLeaves;
import com.github.uright008.nt.explosion.ExplosionModule;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Leaves aggregate loader entry point.
 *
 * <p>Composes the workload modules and their platform shells; the {@code /nt}
 * command bridge lives in core's Leaves shell ({@link NativeThreadingCoreLeaves}).
 * Everything under {@code com.github.uright008.nt} ships inside this jar but is
 * extracted by leavesclip into {@code plugins/.mixins/native-threading.mixins.jar}
 * and loaded by the server's {@code MixinURLClassLoader}; the plugin class loader
 * delegates parent-first to that single copy, so config/state/commands are shared
 * with the mixins.</p>
 *
 * <p>Mixins apply when the target classes load, before plugin enable; this shell
 * only wires module lifecycle.</p>
 */
public final class NativeThreadingLeavesPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        NativeThreadingCoreLeaves.onEnable(getServer());
        new ExplosionModule().onInitialize();
        getLogger().info("NativeThreading enabled — leavesclip native mixin runtime active");
    }

    @Override
    public void onDisable() {
        NativeThreadingCoreLeaves.onDisable();
        ExplosionModule.onServerStopped();
    }
}
