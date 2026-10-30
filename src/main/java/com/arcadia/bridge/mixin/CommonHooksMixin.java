package com.arcadia.bridge.mixin;

import com.arcadia.bridge.minecraft.ChatGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bloque le chat d'un joueur muet (ou en attente de verification) avant que l'evenement de chat ne
 * soit cree.
 *
 * <p>Renvoyer null est exactement ce que fait NeoForge quand l'evenement est annule : le message est
 * abandonne proprement, sans perturber la chaine des messages signes. Et comme aucun ecouteur n'est
 * appele, un mod de mise en forme qui diffuse lui-meme le message (LPC, en priorite HIGHEST) ne le
 * voit jamais.
 */
@Mixin(value = CommonHooks.class, remap = false)
public abstract class CommonHooksMixin {

    @Inject(method = "onServerChatSubmittedEvent", at = @At("HEAD"), cancellable = true)
    private static void arcadiabridge$blockChat(ServerPlayer player, String plain, Component decorated,
                                                CallbackInfoReturnable<Component> cir) {
        if (ChatGuard.shouldBlockPublic(player, plain)) {
            cir.setReturnValue(null);
        }
    }
}
