package com.arcadia.bridge.minecraft;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.sanction.SanctionService;
import net.minecraft.server.level.ServerPlayer;

/**
 * Decide si un message de chat doit etre bloque (joueur muet, ou en attente de verification).
 *
 * <p>Appele avant la creation de l'evenement de chat (voir CommonHooksMixin) : un mod de mise en forme
 * qui diffuse lui-meme le message, comme LPC, ne peut donc pas passer devant, quel que soit l'ordre de
 * chargement des mods.
 */
public final class ChatGuard {

    private ChatGuard() {
    }

    /** @return true si le message doit etre bloque ; le joueur est alors prevenu. */
    public static boolean shouldBlock(ServerPlayer player) {
        Bridge bridge = Bridge.get();
        if (bridge == null || player == null) {
            return false;
        }
        var mute = bridge.cache().activeMute(player.getUUID());
        if (mute.isPresent()) {
            player.sendSystemMessage(SanctionService.muteMessage(mute.get()));
            return true;
        }
        if (BridgeConfig.VERIFICATION_BLOCK_CHAT.get() && bridge.verification().isPending(player.getUUID())) {
            player.sendSystemMessage(Lang.mc("verify.chat_blocked"));
            return true;
        }
        return false;
    }
}
