package com.arcadia.bridge.mixin;

import java.util.UUID;

import com.arcadia.bridge.tabs.ChatTabs;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Message d'equipe FTB Teams : pendant sa diffusion aux membres, on retient qui l'a ecrit, pour que la ligne
 * arrive dans l'onglet Equipe d'ArcadiaChat (voir ServerPlayerChatMixin). Sans FTB Teams, ignore.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.AbstractTeam", remap = false)
public abstract class FtbTeamMessageMixin {

    @Inject(method = "sendMessage(Ljava/util/UUID;Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"),
            require = 0)
    private void arcadiabridge$beginTeamMessage(UUID from, Component text, CallbackInfo ci) {
        ChatTabs.beginTeamMessage(from);
    }

    @Inject(method = "sendMessage(Ljava/util/UUID;Lnet/minecraft/network/chat/Component;)V", at = @At("RETURN"),
            require = 0)
    private void arcadiabridge$endTeamMessage(UUID from, Component text, CallbackInfo ci) {
        ChatTabs.endTeamMessage();
    }
}
