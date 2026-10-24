package com.arcadia.bridge.mixin;

import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Messages de joueurs (chat sans LPC, /msg, messages d'equipe) : pour les joueurs equipes d'ArcadiaChat, ils
 * partent avec leur canal. L'etiquette est posee avant la creation du paquet reseau, donc avant que
 * No Chat Reports ne transforme ces messages en messages systeme : sans elle, un /msg arriverait chez le
 * client comme une simple annonce.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {

    @Shadow
    public abstract boolean acceptsChatMessages();

    /** Diffusion d'un message d'equipe FTB Teams : la ligne part dans l'onglet Equipe d'ArcadiaChat. */
    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void arcadiabridge$tagTeamLine(Component message, boolean overlay, CallbackInfo ci) {
        if (!overlay && ChatTabs.tagTeamLine((ServerPlayer) (Object) this, message)) {
            ci.cancel();
        }
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void arcadiabridge$tagPlayerChat(OutgoingChatMessage message, boolean filtered, ChatType.Bound boundType,
                                             CallbackInfo ci) {
        // Filtrage de texte actif : on laisse Minecraft appliquer ses regles.
        if (filtered || !acceptsChatMessages()) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        var type = boundType.chatType();
        boolean isPrivate = type.is(ChatType.MSG_COMMAND_INCOMING) || type.is(ChatType.MSG_COMMAND_OUTGOING)
                || type.is(ChatType.TEAM_MSG_COMMAND_INCOMING) || type.is(ChatType.TEAM_MSG_COMMAND_OUTGOING);
        String channel = isPrivate ? TabsProtocol.CHANNEL_PRIVATE : TabsProtocol.CHANNEL_LOCAL;
        // Correspondant : celui a qui on ecrit (message sortant), sinon celui qui ecrit.
        String partner = "";
        if (isPrivate) {
            partner = boundType.targetName().map(Component::getString).orElse(boundType.name().getString());
        }
        // Auteur : soi-meme pour un message prive sortant, sinon celui dont le pseudo accompagne le message.
        String sender = boundType.targetName().isPresent() ? self.getGameProfile().getName() : boundType.name().getString();
        if (ChatTabs.send(self, channel, partner, sender, boundType.decorate(message.content()))) {
            ci.cancel();
        }
    }
}
