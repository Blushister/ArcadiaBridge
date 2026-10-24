package com.arcadia.bridge.share;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * Partage dans le chat : [item] (objet en main), [inv] (inventaire), [ender] (coffre de l'Ender) et [pos]
 * (position), avec leurs equivalents francais [objet], [inventaire] et [position].
 *
 * <p>Le mot-cle est remplace par un element interactif : la fiche de l'objet au survol, ou un clic qui ouvre
 * l'inventaire en lecture seule (garde 30 minutes en base, donc visible depuis n'importe quel serveur) ou ajoute
 * un point de repere a la position.
 *
 * <p>Un meme message passe par plusieurs chemins (affichage local, autres serveurs, Discord) : le partage est
 * calcule une fois par joueur et par message, puis reutilise.
 */
public final class Shares {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern TOKEN = Pattern.compile("\\[(item|objet|inv|inventaire|ender|pos|position)\\]",
            Pattern.CASE_INSENSITIVE);
    /** Duree pendant laquelle un partage calcule est reutilise (les chemins d'un meme message). */
    private static final long REUSE_MS = 3000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum Kind { ITEM, INVENTORY, ENDER, POSITION }

    private record Computed(Map<Kind, Component> chat, Map<Kind, String> discord, long at) {
    }

    private static final Map<UUID, Computed> RECENT = new ConcurrentHashMap<>();

    private Shares() {
    }

    public static boolean enabled() {
        return BridgeConfig.CHAT_SHARES.get();
    }

    public static boolean hasToken(String text) {
        return enabled() && text != null && TOKEN.matcher(text).find();
    }

    /** Remplace les mots-cles d'une ligne de chat deja mise en forme. Sans mot-cle, rend la ligne telle quelle. */
    public static Component apply(Component line, ServerPlayer sender) {
        if (sender == null || !hasToken(line.getString())) {
            return line;
        }
        Computed computed = computeFor(sender, kindsIn(line.getString()));
        MutableComponent out = Component.empty();
        line.visit((style, text) -> {
            Matcher matcher = TOKEN.matcher(text);
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) {
                    out.append(Component.literal(text.substring(last, matcher.start())).withStyle(style));
                }
                Component part = computed.chat().get(kindOf(matcher.group(1)));
                out.append(part != null ? part : Component.literal(matcher.group()).withStyle(style));
                last = matcher.end();
            }
            if (last < text.length()) {
                out.append(Component.literal(text.substring(last)).withStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Version texte pour Discord : "[Epee en diamant x1]", "[inventaire]", "[position : Overworld 1 64 2]". */
    public static String forDiscord(String raw, ServerPlayer sender) {
        if (sender == null || !hasToken(raw)) {
            return raw;
        }
        Computed computed = computeFor(sender, kindsIn(raw));
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String text = computed.discord().getOrDefault(kindOf(matcher.group(1)), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Kind kindOf(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "item", "objet" -> Kind.ITEM;
            case "inv", "inventaire" -> Kind.INVENTORY;
            case "ender" -> Kind.ENDER;
            default -> Kind.POSITION;
        };
    }

    private static Set<Kind> kindsIn(String text) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            kinds.add(kindOf(matcher.group(1)));
        }
        return kinds;
    }

    /**
     * Partages demandes par un message : seuls ceux qui manquent sont calcules (un inventaire n'est copie en base
     * que s'il est vraiment partage), puis reutilises par les autres chemins du meme message.
     */
    private static Computed computeFor(ServerPlayer player, Set<Kind> kinds) {
        long now = System.currentTimeMillis();
        Computed recent = RECENT.get(player.getUUID());
        if (recent == null || now - recent.at() >= REUSE_MS) {
            recent = new Computed(new HashMap<>(), new HashMap<>(), now);
            RECENT.put(player.getUUID(), recent);
            RECENT.values().removeIf(c -> now - c.at() > 60_000);
        }
        for (Kind kind : kinds) {
            if (!recent.chat().containsKey(kind)) {
                switch (kind) {
                    case ITEM -> item(player, recent.chat(), recent.discord());
                    case INVENTORY, ENDER -> container(player, kind, recent.chat(), recent.discord());
                    case POSITION -> position(player, recent.chat(), recent.discord());
                }
            }
        }
        return recent;
    }

    // ---------------------------------------------------------------- [item]

    private static void item(ServerPlayer player, Map<Kind, Component> chat, Map<Kind, String> discord) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            chat.put(Kind.ITEM, Lang.mc("share.empty_hand"));
            discord.put(Kind.ITEM, Lang.get("share.empty_hand.discord"));
            return;
        }
        // getDisplayName : nom entre crochets, couleur de rarete, et la fiche complete au survol.
        MutableComponent part = stack.getDisplayName().copy();
        if (stack.getCount() > 1) {
            part.append(Component.literal(" x" + stack.getCount()).withStyle(ChatFormatting.GRAY));
        }
        chat.put(Kind.ITEM, part);
        discord.put(Kind.ITEM, "[" + stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : "") + "]");
    }

    // ---------------------------------------------------------------- [inv] et [ender]

    /** Contenu copie (inventaire : objets, armure, seconde main ; coffre de l'Ender) en SNBT. */
    private static String snapshot(ServerPlayer player, boolean ender) {
        var registries = player.registryAccess();
        ListTag list = new ListTag();
        if (ender) {
            Container chest = player.getEnderChestInventory();
            for (int i = 0; i < chest.getContainerSize(); i++) {
                add(list, i, chest.getItem(i), registries);
            }
        } else {
            var inventory = player.getInventory();
            // Disposition de la vue : lignes 1 a 3 = sac, ligne 4 = barre d'action, ligne 5 = armure et seconde main.
            for (int i = 9; i < 36; i++) {
                add(list, i - 9, inventory.getItem(i), registries);
            }
            for (int i = 0; i < 9; i++) {
                add(list, 27 + i, inventory.getItem(i), registries);
            }
            for (int i = 0; i < 4; i++) {
                add(list, 36 + i, inventory.armor.get(3 - i), registries);
            }
            add(list, 44, inventory.offhand.get(0), registries);
        }
        CompoundTag root = new CompoundTag();
        root.put("items", list);
        return root.toString();
    }

    private static void add(ListTag list, int slot, ItemStack stack, HolderLookup.Provider registries) {
        if (!stack.isEmpty()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("item", stack.save(registries));
            list.add(entry);
        }
    }

    // ---------------------------------------------------------------- [pos]

    private static void position(ServerPlayer player, Map<Kind, Component> chat, Map<Kind, String> discord) {
        BlockPos pos = player.blockPosition();
        String dimension = player.level().dimension().location().toString();
        String label = dimensionName(dimension);
        String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        discord.put(Kind.POSITION, Lang.get("share.position.discord", "dimension", label, "coords", coords));
        chat.put(Kind.POSITION, Lang.mc("share.position", "dimension", label, "coords", coords).withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.mc("share.position.hover")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/partage pos " + Bridge.serverId() + " "
                        + dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))));
    }

    public static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
        };
    }

    private static String newId() {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }
}
