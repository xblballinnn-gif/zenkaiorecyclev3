package com.zenkaiorecycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.Locale;

/**
 * Sends /rtp, clicks the menu item for the resource world (any item whose name or description contains
 * rtp_menu_match), then waits for the teleport to finish.
 */
final class Rtp {
    private enum Step { IDLE, SEND, WAIT_MENU, WAIT_CLOSE, WAIT_TELEPORT }

    static String failure = "";

    private static Step step = Step.IDLE;
    private static int ticks;

    private Rtp() {
    }

    static void start() {
        failure = "";
        go(Step.SEND);
    }

    static void abort(Minecraft mc) {
        step = Step.IDLE;
        if (mc.player != null && mc.screen instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
    }

    private static void go(Step s) {
        step = s;
        ticks = 0;
    }

    static Status tick(Minecraft mc) {
        ticks++;
        LocalPlayer p = mc.player;
        switch (step) {
            case SEND -> {
                if (ticks == 1 && mc.screen != null) {
                    p.closeContainer();
                }
                if (ticks >= 4) {
                    Inv.command(mc, Config.rtpCommand);
                    go(Step.WAIT_MENU);
                }
            }
            case WAIT_MENU -> {
                if (mc.screen instanceof AbstractContainerScreen<?> screen && ticks >= 5) {
                    int slot = findSlot(p, screen.getMenu());
                    if (slot >= 0) {
                        Inv.click(mc, screen.getMenu(), slot, 0, ClickType.PICKUP);
                        go(Step.WAIT_CLOSE);
                    } else if (ticks > 60) {
                        p.closeContainer();
                        failure = "no item in the /" + Config.rtpCommand + " menu mentions \"" + Config.rtpMatch
                                + "\" (menu title: \"" + screen.getTitle().getString() + "\"). Change rtp_menu_match.";
                        step = Step.IDLE;
                        return Status.FAILED;
                    }
                } else if (mc.screen == null && ticks > 60) {
                    go(Step.WAIT_TELEPORT); // no menu: /rtp probably teleported straight away
                }
            }
            case WAIT_CLOSE -> {
                if (mc.screen == null) {
                    go(Step.WAIT_TELEPORT);
                } else if (ticks > 40) {
                    p.closeContainer();
                    go(Step.WAIT_TELEPORT);
                }
            }
            case WAIT_TELEPORT -> {
                if (ticks >= Math.max(1, Config.rtpWaitSeconds) * 20) {
                    step = Step.IDLE;
                    return Status.DONE;
                }
            }
            default -> {
            }
        }
        return Status.RUNNING;
    }

    /** First slot of the open menu (not the player's own inventory) whose item text contains the match text. */
    private static int findSlot(LocalPlayer p, AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (s.container == p.getInventory()) {
                continue;
            }
            ItemStack stack = s.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (Component line : stack.getTooltipLines(p, TooltipFlag.Default.NORMAL)) {
                text.append(line.getString()).append(' ');
            }
            if (text.toString().toLowerCase(Locale.ROOT).contains(Config.rtpMatch)) {
                return s.index;
            }
        }
        return -1;
    }
}
