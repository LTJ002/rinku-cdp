package com.wdcdp.cdpscreen;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Client commands for the browser backend.
 *
 * <p>One command, because there is one thing the player has to be able to do that
 * the mod cannot do for them: sign in. A site that requires an account shows its
 * login dialog over the video, and no amount of hiding it changes the fact that the
 * page is logged out - the dialog comes back on the site's timer. The only real fix
 * is a session cookie in the profile, which needs a real window to type into.</p>
 */
@EventBusSubscriber(modid = CdpScreenMod.MOD_ID, value = Dist.CLIENT)
public final class CdpCommands {

    private CdpCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("wdcdp")
                        .then(Commands.literal("login")
                                .executes(context -> signIn(context.getSource(),
                                        "https://www.bilibili.com"))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> signIn(context.getSource(),
                                                StringArgumentType.getString(context, "url")))))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    String text = de.keksuccino.rinku.Rinku.describe();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(text), false);
                                    return 1;
                                })));
    }

    private static int signIn(net.minecraft.commands.CommandSourceStack source, String url) {
        // Off-thread: opening the window stops the in-game browser first, which
        // waits for the process to exit. Doing that inline would freeze the game for
        // up to three seconds on a command the player typed.
        Thread worker = new Thread(() -> CdpBackend.get().openSignInWindow(url),
                "wdcdp-signin");
        worker.setDaemon(true);
        worker.start();

        source.sendSuccess(() -> Component.literal(
                "正在打开登录窗口...登录后关闭窗口，屏幕会自动恢复"), false);
        return 1;
    }
}
