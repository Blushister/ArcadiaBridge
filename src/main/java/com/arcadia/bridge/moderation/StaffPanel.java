package com.arcadia.bridge.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.Report;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.report.ReportService;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Panel staff d'ArcadiaChat : mode lent et verrou du chat, annonces automatiques et mots filtres. Le client ne
 * fait qu'afficher et demander : chaque action est verifiee ici (permission et valeurs) avant d'aller en base.
 */
public final class StaffPanel {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_'.-]{1,63}\\*?");
    private static final long MIN_INTERVAL_MS = 250;
    private static final int MAX_REPORTS = 50;
    private static final Map<UUID, Long> LAST_ACTION = new ConcurrentHashMap<>();

    private StaffPanel() {
    }

    /** Sections du panel permises au joueur (0 : aucune). */
    public static int access(ServerPlayer player) {
        int access = 0;
        if (Permissions.has(player, Permissions.CHAT_MODERATE)) {
            access |= TabsProtocol.PANEL_RULES;
        }
        if (Permissions.has(player, Permissions.ANNOUNCEMENTS)) {
            access |= TabsProtocol.PANEL_ANNOUNCEMENTS;
        }
        if (Permissions.has(player, Permissions.CHAT_FILTER)) {
            access |= TabsProtocol.PANEL_FILTERS;
        }
        if (Permissions.has(player, Permissions.CHAT_MODERATE)) {
            access |= TabsProtocol.PANEL_PINS;
        }
        if (Permissions.has(player, Permissions.REPORTS) && ReportService.isEnabled()) {
            access |= TabsProtocol.PANEL_REPORTS;
        }
        return access;
    }

    public static void onRequest(TabsProtocol.PanelRequest payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && Bridge.get() != null) {
            send(player);
        }
    }

    public static void onLeave(ServerPlayer player) {
        LAST_ACTION.remove(player.getUUID());
    }

    /** Contenu du panel, limite aux sections permises au joueur (les signalements sont lus en base d'abord). */
    public static void send(ServerPlayer player) {
        Bridge bridge = Bridge.get();
        if (bridge == null || player.hasDisconnected()) {
            return;
        }
        int access = access(player);
        Repository repo = bridge.repository();
        if ((access & TabsProtocol.PANEL_REPORTS) != 0 && repo != null) {
            repo.openReports(MAX_REPORTS).thenAccept(list -> bridge.runOnMain(() -> send(player, access, list
                    .stream().map(r -> new TabsProtocol.ReportEntry(r.id(), r.targetName(), r.reporterName(), r.reason(),
                            r.serverId(), r.createdAt(), r.count())).toList())))
                    .exceptionally(error -> {
                        LOGGER.warn("[ArcadiaBridge] Signalements illisibles : {}", error.toString());
                        bridge.runOnMain(() -> send(player, access, List.of()));
                        return null;
                    });
            return;
        }
        send(player, access, List.of());
    }

    private static void send(ServerPlayer player, int access, List<TabsProtocol.ReportEntry> reports) {
        Bridge bridge = Bridge.get();
        if (bridge == null || player.hasDisconnected()) {
            return;
        }
        ChatModeration moderation = bridge.moderation();
        List<TabsProtocol.ServerInfo> servers = new ArrayList<>(ChatTabs.servers());
        if (servers.stream().noneMatch(server -> server.id().equals(Bridge.serverId()))) {
            String name = BridgeConfig.SERVER_NAME.get();
            servers.add(0, new TabsProtocol.ServerInfo(Bridge.serverId(), name.isBlank() ? Bridge.serverId() : name, ""));
        }
        List<TabsProtocol.RuleEntry> rules = (access & TabsProtocol.PANEL_RULES) == 0 ? List.of()
                : moderation.rules().values().stream()
                .map(rule -> new TabsProtocol.RuleEntry(rule.scope(), rule.slowSeconds(), rule.locked())).toList();
        List<TabsProtocol.AnnouncementEntry> announcements = (access & TabsProtocol.PANEL_ANNOUNCEMENTS) == 0 ? List.of()
                : moderation.announcements().stream().map(a -> new TabsProtocol.AnnouncementEntry(a.id(), a.message(),
                        a.intervalMinutes(), a.servers(), a.discord(), a.enabled())).toList();
        List<TabsProtocol.FilterEntry> filters = (access & TabsProtocol.PANEL_FILTERS) == 0 ? List.of()
                : moderation.filters().stream().map(f -> new TabsProtocol.FilterEntry(f.word(), f.block())).toList();
        List<TabsProtocol.PinEntry> pins = (access & TabsProtocol.PANEL_PINS) == 0 ? List.of()
                : moderation.pins().entrySet().stream()
                .map(entry -> new TabsProtocol.PinEntry(entry.getKey(), entry.getValue())).toList();
        PacketDistributor.sendToPlayer(player, new TabsProtocol.PanelData(access, servers, rules, announcements, filters,
                pins, reports));
    }

    public static void onAction(TabsProtocol.PanelAction payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_ACTION.put(player.getUUID(), now);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        CompletableFuture<Void> done;
        try {
            done = perform(bridge, player, payload.kind(), payload.args());
        } catch (RuntimeException e) {
            // Valeurs invalides (client modifie) : on renvoie simplement l'etat actuel.
            done = null;
        }
        if (done == null) {
            send(player);
            return;
        }
        done.whenComplete((v, error) -> {
            if (error != null) {
                LOGGER.warn("[ArcadiaBridge] Action du panel staff echouee : {}", error.toString());
            }
            bridge.runOnMain(() -> send(player));
        });
    }

    /** "*" ou une liste d'identifiants de serveurs valides ; null sinon. */
    private static String servers(String value) {
        if (ChatModeration.ALL.equals(value)) {
            return value;
        }
        List<String> ids = new ArrayList<>();
        for (String id : value.split(",")) {
            String clean = id.trim();
            if (!SERVER_ID.matcher(clean).matches()) {
                return null;
            }
            ids.add(clean);
        }
        String joined = String.join(",", ids);
        return ids.isEmpty() || joined.length() > 255 ? null : joined;
    }
}
