package com.arcadia.bridge.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.Poll;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Durations;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Sondages communs a tous les serveurs : le staff pose une question, les joueurs votent en cliquant sur un choix,
 * et les resultats sont annonces a la fin, en jeu et sur Discord.
 *
 * <p>Le serveur ou le sondage a ete cree le cloture lui-meme a l'heure prevue (ou a son redemarrage s'il etait
 * eteint) : l'annonce et les resultats partent ainsi dans le meme salon Discord.
 */
public final class PollService {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Canal interne du relais : annonce ou resultats d'un sondage (ligne deja mise en forme). */
    public static final String RELAY = "poll";
    public static final int MAX_OPTIONS = 6;

    private final Bridge bridge;

    public PollService(Bridge bridge) {
        this.bridge = bridge;
    }

    public static boolean enabled() {
        return BridgeConfig.CHAT_POLLS.get();
    }

    /**
     * Cree un sondage : "Question ? | Choix 1 | Choix 2", avec une duree facultative en tete ("30m Question ? | ...").
     *
     * @return message d'erreur pour le createur, ou null si le sondage est lance
     */
    public Component create(ServerPlayer creator, String input) {
        String text = input.trim();
        long duration = TimeUnit.MINUTES.toMillis(BridgeConfig.CHAT_POLL_MINUTES.get());
        int space = text.indexOf(' ');
        if (space > 0) {
            long parsed = Durations.parse(text.substring(0, space));
            if (parsed > 0) {
                duration = Math.min(parsed, TimeUnit.DAYS.toMillis(7));
                text = text.substring(space + 1).trim();
            }
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[|;]")) {
            String clean = Text.forMinecraft(part, 100).trim();
            if (!clean.isEmpty()) {
                parts.add(clean);
            }
        }
        if (parts.size() < 3 || parts.size() > MAX_OPTIONS + 1) {
            return Lang.mc("poll.usage");
        }
        String question = parts.get(0);
        List<String> options = parts.subList(1, parts.size());
        String name = creator.getGameProfile().getName();
        long endsAt = System.currentTimeMillis() + duration;
        long minutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(duration));
        bridge.repository().createPoll(question, options, name, Bridge.serverId(), endsAt).thenAccept(id -> {
            Poll poll = new Poll(id, question, options, name, Bridge.serverId(), endsAt, false);
            bridge.runOnMain(() -> broadcast(announcement(poll, minutes)));
            if (bridge.discord().isReady()) {
                StringBuilder lines = new StringBuilder();
                for (int i = 0; i < options.size(); i++) {
                    lines.append("\n").append(i + 1).append(". ").append(Text.forDiscord(options.get(i)));
                }
                bridge.discord().sendChat(Lang.get("poll.discord.new", "name", Text.forDiscord(name),
                        "question", Text.forDiscord(question), "options", lines.toString(), "minutes", minutes));
            }
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Sondage non cree : {}", error.toString());
            bridge.runOnMain(() -> creator.sendSystemMessage(Lang.mc("common.error")));
            return null;
        });
        return null;
    }

    public CompletableFuture<Component> vote(ServerPlayer player, long pollId, int choice) {
        return bridge.repository().vote(pollId, player.getUUID(), choice - 1).thenCompose(result -> switch (result) {
            case OK -> bridge.repository().loadPoll(pollId).thenApply(poll -> (Component) Lang.mc("poll.voted",
                    "choice", poll.options().get(choice - 1)));
            case CLOSED -> CompletableFuture.completedFuture((Component) Lang.mc("poll.closed"));
            case BAD_CHOICE -> CompletableFuture.completedFuture((Component) Lang.mc("poll.bad_choice"));
            case UNKNOWN -> CompletableFuture.completedFuture((Component) Lang.mc("poll.unknown", "id", pollId));
        });
    }

    public CompletableFuture<Component> results(long pollId) {
        return bridge.repository().loadPoll(pollId).thenCompose(poll -> poll == null
                ? CompletableFuture.completedFuture((Component) Lang.mc("poll.unknown", "id", pollId))
                : bridge.repository().pollResults(pollId, poll.options().size()).thenApply(counts -> resultsLine(poll, counts)));
    }

    /** Cloture a la main (staff). */
    public CompletableFuture<Component> close(long pollId) {
        return bridge.repository().loadPoll(pollId).thenCompose(poll -> {
            if (poll == null) {
                return CompletableFuture.completedFuture((Component) Lang.mc("poll.unknown", "id", pollId));
            }
            return finish(poll).thenApply(done -> (Component) Lang.mc(done ? "poll.ended" : "poll.closed"));
        });
    }

    /** A chaque synchronisation : cloture les sondages de ce serveur arrives a leur terme. */
    public void closeDue() {
        Repository repo = bridge.repository();
        if (repo == null || !enabled()) {
            return;
        }
        for (Poll poll : repo.duePolls(Bridge.serverId(), System.currentTimeMillis()).join()) {
            finish(poll).join();
        }
    }

    /** Cloture un sondage (une seule fois, meme si deux serveurs essaient) et annonce les resultats partout. */
    private CompletableFuture<Boolean> finish(Poll poll) {
        Repository repo = bridge.repository();
        return repo.closePoll(poll.id()).thenCompose(closed -> {
            if (!closed) {
                return CompletableFuture.completedFuture(false);
            }
            return repo.pollResults(poll.id(), poll.options().size()).thenApply(counts -> {
                Component line = resultsLine(poll, counts);
                bridge.runOnMain(() -> broadcast(line));
                if (bridge.discord().isReady()) {
                    bridge.discord().sendChat(Lang.get("poll.discord.results", "question", Text.forDiscord(poll.question()),
                            "results", discordResults(poll, counts)));
                }
                return true;
            });
        });
    }

    /** Diffuse ici a tous les joueurs, et aux autres serveurs par le relais. */
    private void broadcast(Component line) {
        // Sur la ligne du serveur : visible dans les onglets Tout, Global et Serveur d'ArcadiaChat.
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            ChatTabs.deliver(player, TabsProtocol.CHANNEL_LOCAL, Bridge.serverId(), "", line);
        }
        Repository repo = bridge.repository();
        if (repo != null) {
            repo.publishChat(Bridge.serverId(), "", "", line.getString(),
                    CrossServerChat.toJson(line, bridge.server().registryAccess()), RELAY);
        }
    }

    // ---------------------------------------------------------------- mise en forme

    private static Component announcement(Poll poll, long minutes) {
        MutableComponent line = Lang.mc("poll.header", "name", poll.createdBy(), "question", poll.question());
        for (int i = 0; i < poll.options().size(); i++) {
            String option = poll.options().get(i);
            int number = i + 1;
            line.append(Component.literal(" "));
            line.append(Component.literal("[" + option + "]").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.mc("poll.vote.hover", "choice", option)))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/sondage voter " + poll.id() + " " + number))));
        }
        line.append(Lang.mc("poll.footer", "minutes", minutes, "id", poll.id()));
        return line;
    }

    private static Component resultsLine(Poll poll, int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        MutableComponent line = Lang.mc("poll.results", "question", poll.question(), "total", total);
        for (int i = 0; i < counts.length; i++) {
            int percent = total == 0 ? 0 : Math.round(counts[i] * 100f / total);
            line.append(Lang.mc("poll.results.option", "choice", poll.options().get(i), "count", counts[i],
                    "percent", percent));
        }
        return line;
    }

    private static String discordResults(Poll poll, int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            int percent = total == 0 ? 0 : Math.round(counts[i] * 100f / total);
            out.append("\n").append(Text.forDiscord(poll.options().get(i))).append(" : **").append(counts[i])
                    .append("** (").append(percent).append(" %)");
        }
        out.append("\n").append(Lang.get("poll.discord.total", "total", total));
        return out.toString();
    }

}
