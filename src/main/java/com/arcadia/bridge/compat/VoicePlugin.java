package com.arcadia.bridge.compat;

import com.arcadia.bridge.Bridge;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/** Point d'entree dans Simple Voice Chat, trouve par le mod s'il est installe : sans lui, cette classe n'est jamais chargee. */
@ForgeVoicechatPlugin
public final class VoicePlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "arcadiabridge";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            VoiceCompat.setApi(event.getVoicechat());
            VoiceRelay.start(event.getVoicechat());
        });
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> VoiceRelay.stop());
        registration.registerEvent(MicrophonePacketEvent.class, VoiceRelay::onMicrophone);
        registration.registerEvent(JoinGroupEvent.class, event -> changed());
        registration.registerEvent(LeaveGroupEvent.class, event -> changed());
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> changed());
        registration.registerEvent(PlayerStateChangedEvent.class, event -> changed());
    }

    private static void changed() {
        Bridge bridge = Bridge.get();
        if (bridge != null) {
            bridge.voice().broadcastSoon();
        }
    }
}
