package com.arcadia.bridge.mixin;

import com.arcadia.bridge.config.BridgeConfig;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat non signalable : la signature des messages n'est pas exigee, meme avec enforce-secure-profile. Les joueurs
 * equipes d'ArcadiaChat ne signent alors plus leurs messages, et le serveur annonce un chat non verifie.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true)
    private void arcadiabridge$noSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        if (BridgeConfig.CHAT_NO_REPORTS.get()) {
            cir.setReturnValue(false);
        }
    }
}
