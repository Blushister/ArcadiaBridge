package com.arcadia.bridge.compat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.mojang.logging.LogUtils;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.slf4j.Logger;

/**
 * Relie les salons vocaux entre les serveurs : la voix des joueurs d'ici part vers le relais (voicehub), et celle des autres
 * serveurs est rejouee chez les occupants d'ici. Le micro et le son restent ceux de Simple Voice Chat.
 *
 * <p>Tout tourne sur les fils du reseau vocal, jamais sur celui du jeu : le relais ne coute rien au TPS. Une panne du relais
 * ne coupe que l'audio entre serveurs ; le vocal de chaque serveur continue.
 */
public final class VoiceRelay {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final byte[] MAGIC = {'A', 'V', 1};
    private static final byte KIND_PRESENCE = 0;
    private static final byte KIND_AUDIO = 1;
    private static final byte KIND_ACK = 2;
    /** Sans accuse de reception depuis ce delai, le relais est considere injoignable. */
    private static final long ACK_TIMEOUT_MS = 25_000;
    private volatile long lastAck;
    private static final int TAG_SIZE = 16;
    private static final int NONCE_SIZE = 12;
    private static final int HEARTBEAT_MS = 10_000;
    private static final long SPEAKER_TIMEOUT_MS = 30_000;

    private static volatile VoiceRelay instance;

    /** Un joueur d'un autre serveur qui parle : son canal, vers les occupants d'ici de son salon. */
    private static final class Speaker {
        final StaticAudioChannel channel;
        String room = "";
        int generation = -1;
        long lastHeard;

        Speaker(StaticAudioChannel channel) {
            this.channel = channel;
        }
    }

    private final VoicechatServerApi api;
    private final DatagramSocket socket;
    private final InetSocketAddress hub;
    private final SecretKeySpec aesKey;
    private final SecretKeySpec macKey;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Speaker> speakers = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private VoiceRelay(VoicechatServerApi api, InetSocketAddress hub, String secret) throws Exception {
        this.api = api;
        this.hub = hub;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        this.macKey = new SecretKeySpec(sha.digest((secret + "|hmac").getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        this.aesKey = new SecretKeySpec(Arrays.copyOf(sha.digest((secret + "|aes").getBytes(StandardCharsets.UTF_8)), 16), "AES");
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(HEARTBEAT_MS);
    }

    static void start(VoicechatServerApi api) {
        String host = BridgeConfig.VOICE_RELAY_HOST.get().trim();
        String secret = BridgeConfig.VOICE_RELAY_SECRET.get();
        if (host.isEmpty() || secret.isBlank()) {
            return;
        }
        try {
            VoiceRelay relay = new VoiceRelay(api, new InetSocketAddress(host, BridgeConfig.VOICE_RELAY_PORT.get()), secret);
            instance = relay;
            Thread receiver = new Thread(relay::receiveLoop, "ArcadiaBridge-VoiceRelay");
            receiver.setDaemon(true);
            receiver.start();
            LOGGER.info("[ArcadiaBridge] Relais vocal : {}:{}.", host, BridgeConfig.VOICE_RELAY_PORT.get());
        } catch (Exception e) {
            LOGGER.warn("[ArcadiaBridge] Relais vocal indisponible : {}", e.toString());
        }
    }

    public static void stop() {
        VoiceRelay relay = instance;
        instance = null;
        if (relay != null) {
            relay.running = false;
            relay.socket.close();
        }
    }

    public static boolean active() {
        return instance != null;
    }

    /** Le relais a-t-il repondu recemment ? */
    public static boolean reachable() {
        VoiceRelay relay = instance;
        return relay != null && System.currentTimeMillis() - relay.lastAck < ACK_TIMEOUT_MS;
    }

    // ---------------------------------------------------------------- envoi

    /** Un joueur d'ici parle : si c'est dans un salon vocal, sa voix part vers les autres serveurs. */
    static void onMicrophone(MicrophonePacketEvent event) {
        VoiceRelay relay = instance;
        VoicechatConnection sender = event.getSenderConnection();
        if (relay == null || sender == null) {
            return;
        }
        Group group = sender.getGroup();
        String room = group == null ? null : VoiceCompat.roomOfGroup(group.getId());
        if (room != null) {
            relay.sendAudio(room, sender.getPlayer().getUuid(), event.getPacket().getOpusEncodedData());
        }
    }

    private void sendAudio(String room, UUID speaker, byte[] opus) {
        try {
            byte[] name = room.getBytes(StandardCharsets.UTF_8);
            ByteBuffer plain = ByteBuffer.allocate(1 + name.length + 16 + opus.length);
            plain.put((byte) name.length).put(name).putLong(speaker.getMostSignificantBits()).putLong(speaker.getLeastSignificantBits()).put(opus);
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(plain.array());
            ByteBuffer body = ByteBuffer.allocate(NONCE_SIZE + sealed.length);
            body.put(nonce).put(sealed);
            send(KIND_AUDIO, body.array());
        } catch (Exception e) {
            LOGGER.debug("[ArcadiaBridge] Voix non relayee : {}", e.toString());
        }
    }

    private void send(byte kind, byte[] content) throws Exception {
        ByteBuffer body = ByteBuffer.allocate(MAGIC.length + 1 + content.length);
        body.put(MAGIC).put(kind).put(content);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(macKey);
        byte[] tag = Arrays.copyOf(mac.doFinal(body.array()), TAG_SIZE);
        byte[] packet = Arrays.copyOf(body.array(), body.capacity() + TAG_SIZE);
        System.arraycopy(tag, 0, packet, body.capacity(), TAG_SIZE);
        socket.send(new DatagramPacket(packet, packet.length, hub));
    }

    private void sendPresence() {
        try {
            byte[] id = Bridge.serverId().getBytes(StandardCharsets.UTF_8);
            ByteBuffer content = ByteBuffer.allocate(8 + id.length);
            content.putLong(System.currentTimeMillis()).put(id);
            send(KIND_PRESENCE, content.array());
        } catch (Exception e) {
            LOGGER.debug("[ArcadiaBridge] Presence du relais vocal : {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- reception

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        long lastPresence = 0;
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastPresence >= HEARTBEAT_MS) {
                lastPresence = now;
                sendPresence();
                speakers.values().removeIf(speaker -> now - speaker.lastHeard > SPEAKER_TIMEOUT_MS);
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                onPacket(Arrays.copyOf(packet.getData(), packet.getLength()));
            } catch (SocketTimeoutException e) {
                // Rien recu : on repasse par la presence.
            } catch (SocketException e) {
                if (running) {
                    LOGGER.debug("[ArcadiaBridge] Relais vocal : {}", e.toString());
                }
            } catch (Exception e) {
                LOGGER.debug("[ArcadiaBridge] Paquet vocal ignore : {}", e.toString());
            }
        }
    }

    private void onPacket(byte[] packet) throws GeneralSecurityException {
        int headerSize = MAGIC.length + 1;
        if (packet.length < headerSize + TAG_SIZE) {
            return;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(macKey);
        mac.update(packet, 0, packet.length - TAG_SIZE);
        byte[] expected = Arrays.copyOf(mac.doFinal(), TAG_SIZE);
        byte[] tag = Arrays.copyOfRange(packet, packet.length - TAG_SIZE, packet.length);
        if (!MessageDigest.isEqual(expected, tag)) {
            return;
        }
        if (packet[MAGIC.length] == KIND_ACK) {
            lastAck = System.currentTimeMillis();
            return;
        }
        if (packet[MAGIC.length] != KIND_AUDIO || packet.length < headerSize + TAG_SIZE + NONCE_SIZE) {
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, packet, headerSize, NONCE_SIZE));
        byte[] plain = cipher.doFinal(packet, headerSize + NONCE_SIZE, packet.length - headerSize - NONCE_SIZE - TAG_SIZE);
        ByteBuffer in = ByteBuffer.wrap(plain);
        byte[] name = new byte[in.get() & 0xFF];
        in.get(name);
        UUID speakerId = new UUID(in.getLong(), in.getLong());
        byte[] opus = new byte[in.remaining()];
        in.get(opus);
        play(new String(name, StandardCharsets.UTF_8), speakerId, opus);
    }

    /** Rejoue la voix chez les occupants d'ici du salon. */
    private void play(String room, UUID speakerId, byte[] opus) {
        VoiceCompat.Listeners listeners = VoiceCompat.listeners(room);
        if (listeners == null || listeners.connections().isEmpty()) {
            return;
        }
        Speaker speaker = speakers.computeIfAbsent(speakerId, id -> {
            StaticAudioChannel channel = api.createStaticAudioChannel(id);
            if (channel == null) {
                return null;
            }
            // Les salons sont des groupes isoles : ce son vient d'ailleurs, il doit y entrer quand meme.
            channel.setBypassGroupIsolation(true);
            return new Speaker(channel);
        });
        if (speaker == null) {
            return;
        }
        speaker.lastHeard = System.currentTimeMillis();
        if (!room.equals(speaker.room) || speaker.generation != listeners.generation()) {
            speaker.room = room;
            speaker.generation = listeners.generation();
            speaker.channel.clearTargets();
            List<VoicechatConnection> targets = listeners.connections();
            targets.forEach(speaker.channel::addTarget);
        }
        if (opus.length == 0) {
            speaker.channel.flush();
        } else {
            speaker.channel.send(opus);
        }
    }
}
