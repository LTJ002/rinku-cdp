package com.wdcdp.cdpscreen;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Mod entry point.
 *
 * <p>Deliberately almost empty. All the event wiring lives on
 * {@link CdpHooks}, which is registered with {@code @EventBusSubscriber} so the
 * bus can read event types from the method signatures - see the note there about
 * why a lambda listener silently never fired and left every screen black.</p>
 *
 * <p>The constructor only takes the mod bus so that NeoForge instantiates this
 * class during loading; that is what makes the mod "present" and gives the
 * {@code rinku} facade a mod container to belong to.</p>
 */
@Mod(CdpScreenMod.MOD_ID)
public final class CdpScreenMod {

    /**
     * The mod id.
     *
     * <p><b>Must be {@code "rinku"}, not a name of our own.</b> WebDisplays gates
     * everything on {@code ModList.get().isLoaded("rinku")} and declares a
     * required dependency on that id, so any other value means the game refuses to
     * start (missing dependency) or every screen stays blank (the isLoaded check).
     * The real Rinku mod therefore has to be removed from {@code mods/}.</p>
     */
    public static final String MOD_ID = "rinku";

    public CdpScreenMod(IEventBus modBus) {
        CdpLog.info("CDP browser backend loaded as mod '" + MOD_ID + "'");
    }
}
