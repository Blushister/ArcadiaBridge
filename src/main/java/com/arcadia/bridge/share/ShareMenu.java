package com.arcadia.bridge.share;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Vue en lecture seule d'un inventaire partage. Cote client, c'est un coffre ordinaire (MenuType generique) ;
 * cote serveur, aucun objet ne peut etre pris ni depose : les emplacements du partage refusent tout, et les
 * clics comme les transferts rapides sont ignores (y compris ceux des mods de tri d'inventaire).
 */
public final class ShareMenu extends AbstractContainerMenu {

    private final Container container;
    private final int rows;

    private ShareMenu(MenuType<?> type, int id, Inventory playerInventory, Container container, int rows) {
        super(type, id);
        this.container = container;
        this.rows = rows;
        // Meme disposition que le coffre vanilla, pour correspondre a l'ecran du client.
        int offset = (rows - 4) * 18;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ReadOnlySlot(container, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + offset));
        }
    }

    public static ShareMenu fiveRows(int id, Inventory playerInventory, Container container) {
        return new ShareMenu(MenuType.GENERIC_9x5, id, playerInventory, container, 5);
    }

    public static ShareMenu threeRows(int id, Inventory playerInventory, Container container) {
        return new ShareMenu(MenuType.GENERIC_9x3, id, playerInventory, container, 3);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Lecture seule : aucun clic ne modifie quoi que ce soit.
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    /** Emplacement d'un inventaire partage : on ne peut ni y prendre ni y poser un objet. */
    private static final class ReadOnlySlot extends Slot {
        ReadOnlySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }
    }

    public int rows() {
        return rows;
    }

    public Container container() {
        return container;
    }
}
