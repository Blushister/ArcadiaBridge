package com.arcadia.bridge;

import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.discord.DiscordLogs;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.minecraft.GameEvents;
import com.arcadia.bridge.util.Text;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(ArcadiaBridge.MOD_ID)
public final class ArcadiaBridge {

    public static final String MOD_ID = "arcadiabridge";

    public ArcadiaBridge(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, BridgeConfig.SPEC, "arcadiabridge.toml");
        NeoForge.EVENT_BUS.register(GameEvents.class);
        NeoForge.EVENT_BUS.addListener(ArcadiaBridge::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ArcadiaBridge::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ArcadiaBridge::onServerStopping);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Bridge.start(event.getServer());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge != null && BridgeConfig.STATUS_SERVER.get()) {
            // Le bot se connecte en arriere-plan : on attend qu'il soit pret (30 s max) pour annoncer le demarrage.
            Thread announcer = new Thread(() -> {
                for (int i = 0; i < 30 && !bridge.discord().isReady() && !bridge.isStopping(); i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                announceServer(bridge, Lang.get("discord.status.started", "server", serverLabel()), true, false);
            }, "ArcadiaBridge-Announce");
            announcer.setDaemon(true);
            announcer.start();
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge != null && BridgeConfig.STATUS_SERVER.get()) {
            announceServer(bridge, Lang.get("discord.status.stopped", "server", serverLabel()), false, true);
        }
        Bridge.stop();
    }

    private static void announceServer(Bridge bridge, String message, boolean online, boolean blocking) {
        if (!BridgeConfig.isRichPresentation()) {
            if (blocking) {
                bridge.discord().sendChatBlocking(message);
            } else {
                bridge.discord().sendChat(message);
            }
            return;
        }
        var embed = DiscordLogs.serverStatus(message, online);
        if (blocking) {
            bridge.discord().sendChatEmbedBlocking(embed);
        } else {
            bridge.discord().sendChatEmbed(embed);
        }
    }

    private static String serverLabel() {
        String name = BridgeConfig.SERVER_NAME.get();
        return Text.forDiscord(name.isBlank() ? BridgeConfig.SERVER_ID.get() : name);
    }
}
