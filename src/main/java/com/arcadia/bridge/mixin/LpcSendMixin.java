package com.arcadia.bridge.mixin;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.share.Shares;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LPC diffuse le chat local joueur par joueur. Pour ceux qui ont ArcadiaChat, la ligne part avec le canal
 * "local", pour qu'elle arrive dans le bon onglet. Sans LPC, ou si LPC change un jour cette methode,
 * cette accroche est simplement ignoree : le chat reste affiche, dans l'onglet par defaut.
 */
@Pseudo
@Mixin(targets = "com.lpcneoforge.lpcmod.server.LPCEvents", remap = false)
public abstract class LpcSendMixin {

    @ModifyVariable(method = "broadcastMessage", at = @At("HEAD"), argsOnly = true, require = 0)
    private static MutableComponent arcadiabridge$shares(MutableComponent message) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return message;
        }
        String sender = ChatTabs.senderOf(bridge.server(), message);
        ServerPlayer player = sender.isEmpty() ? null : bridge.server().getPlayerList().getPlayerByName(sender);
        Component shared = Shares.hasToken(message.getString()) ? Shares.apply(message, player) : message;
        // Mots masques et @everyone desamorce (panel staff).
        shared = bridge.moderation().clean(player, shared);
        return shared == message ? message : Component.empty().append(shared);
    }

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true, require = 0)
    private static void arcadiabridge$tagLocalChat(Player player, MutableComponent message, boolean emptyLine,
                                                    CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && ChatTabs.supports(serverPlayer)
                && ChatTabs.send(serverPlayer, TabsProtocol.CHANNEL_LOCAL, "", ChatTabs.senderOf(serverPlayer, message),
                message)) {
            ci.cancel();
        }
    }
}
