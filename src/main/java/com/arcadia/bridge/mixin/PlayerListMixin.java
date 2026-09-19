package com.arcadia.bridge.mixin;

import java.net.SocketAddress;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.sanction.SanctionService;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuse la connexion d'un joueur banni avant qu'il n'entre dans le monde, comme le ban vanilla.
 * Lecture du cache memoire uniquement : aucun acces base sur le thread principal.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void arcadiabridge$checkBan(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        Bridge bridge = Bridge.get();
        if (bridge == null || profile.getId() == null) {
            return;
        }
        bridge.cache().activeBan(profile.getId()).ifPresent(ban -> cir.setReturnValue(SanctionService.banScreen(ban)));
    }
}
