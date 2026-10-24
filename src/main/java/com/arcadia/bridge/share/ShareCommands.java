package com.arcadia.bridge.share;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.i18n.Lang;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Commandes declenchees en cliquant sur un partage du chat : /partage voir (inventaire en lecture seule) et
 * /partage pos (point de repere FTB Chunks).
 */
public final class ShareCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ShareCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("partage")
                .then(Commands.literal("voir")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ShareCommands::view)))
                .then(Commands.literal("pos")
                        .then(Commands.argument("server", StringArgumentType.word())
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ShareCommands::position))))))));
    }

    /** Ouvre un inventaire partage, en lecture seule, s'il n'a pas expire. */
    private static int view(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Bridge bridge = Bridge.get();
        if (player == null || bridge == null || bridge.repository() == null) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        bridge.repository().loadShare(id).thenAccept(share -> bridge.runOnMain(() -> {
            if (share == null) {
                player.sendSystemMessage(Lang.mc("share.expired"));
                return;
            }
            boolean ender = "ender".equals(share.kind());
            SimpleContainer container = new SimpleContainer(ender ? 27 : 45);
            try {
                CompoundTag root = TagParser.parseTag(share.data());
                ListTag items = root.getList("items", Tag.TAG_COMPOUND);
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag entry = items.getCompound(i);
                    int slot = entry.getInt("slot");
                    if (slot >= 0 && slot < container.getContainerSize()) {
                        container.setItem(slot, ItemStack.parseOptional(player.registryAccess(), entry.getCompound("item")));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[ArcadiaBridge] Partage {} illisible : {}", id, e.toString());
                player.sendSystemMessage(Lang.mc("share.expired"));
                return;
            }
            var title = Lang.mc(ender ? "share.ender.title" : "share.inventory.title", "name", share.owner());
            player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> ender
                    ? ShareMenu.threeRows(containerId, inventory, container)
                    : ShareMenu.fiveRows(containerId, inventory, container), title));
        }));
        return 1;
    }

    /** Point de repere FTB Chunks a une position partagee (meme serveur seulement : sinon, autre monde). */
    private static int position(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String server = StringArgumentType.getString(ctx, "server");
        String dimension = StringArgumentType.getString(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        String coords = x + ", " + y + ", " + z;
        if (!server.equals(Bridge.serverId())) {
            player.sendSystemMessage(Lang.mc("share.position.other_server", "server", server, "coords", coords));
            return 1;
        }
        if (!ModList.get().isLoaded("ftbchunks") || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            player.sendSystemMessage(Lang.mc("share.position.coords", "dimension", Shares.dimensionName(dimension),
                    "coords", coords));
            return 1;
        }
        String name = Lang.get("share.position.waypoint", "coords", coords).replace("\"", "");
        // Execute au nom du joueur : il lui faut la permission habituelle d'ajouter un point de repere.
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                "ftbchunks waypoint add \"" + name + "\" " + x + " " + y + " " + z + " " + dimension);
        return 1;
    }
}
