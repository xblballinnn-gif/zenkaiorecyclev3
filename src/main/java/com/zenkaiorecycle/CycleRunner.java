package com.zenkaiorecycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * The loop: mine with Baritone until the inventory is full of ore, #stop, /craft blocks (keeping the reserve),
 * /vault everything into the right vault, switch ore, repeat. Starts with gold.
 */
final class CycleRunner {
    private enum State { IDLE, MINING, STOPPING, CRAFTING, DEPOSITING, CLEANUP, RTP, RECONNECTING }

    private static State state = State.IDLE;
    private static OreKind kind = OreKind.GOLD;
    private static int ticks, junkTimer, spreadTimer, lastOre, noProgress;
    private static int rtpCooldown, pickTimer;
    /** True once the pickaxe dropped below the minimum: mine coal (mending) until it is repaired. */
    private static boolean lowPick;
    private static int cycles, rtps, reconnects;
    private static long startedAt;
    private static ServerData lastServer;
    private static int reconnectTimer, reconnectTries, joinWait;
    /** After a reconnect the loop only mines once /rtp has really moved the player away from where they joined. */
    private static boolean verifyRtp;
    private static int rtpRetries;
    private static double rtpFromX, rtpFromZ;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> rtpFromDim;

    private CycleRunner() {
    }

    static boolean running() {
        return state != State.IDLE;
    }

    static void toggle(Minecraft mc) {
        if (running()) {
            stop(mc, "Cycle stopped.");
            return;
        }
        if (!Inv.baritoneLooksInstalled()) {
            Inv.tell(mc, "Baritone was not detected, so nothing was sent. Install Baritone first.");
            return;
        }
        kind = OreKind.GOLD;
        cycles = 0;
        rtps = 0;
        reconnects = 0;
        lowPick = false;
        rtpCooldown = 0;
        startedAt = System.currentTimeMillis();
        beginMining(mc);
    }

    /** Ends the loop (if it is running) and always sends Baritone's stop. */
    static void stop(Minecraft mc, String why) {
        state = State.IDLE;
        AutoEat.abort(mc);
        Spread.abort(mc);
        Rtp.abort(mc);
        if (mc.player != null && mc.screen instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        if (Inv.baritoneLooksInstalled()) {
            Inv.chat(mc, Config.stopCommand);
        }
        Inv.tell(mc, why);
    }

    private static void beginMining(Minecraft mc) {
        state = State.MINING;
        ticks = 0;
        junkTimer = 0;
        spreadTimer = 0;
        noProgress = 0;
        lastOre = mc.player.getInventory().countItem(kind.ore);
        Inv.chat(mc, kind.mineCommand());
        Inv.tell(mc, "Mining " + kind.label + ".");
    }

    static void tick(Minecraft mc) {
        if (state == State.IDLE) {
            return;
        }
        if (state == State.RECONNECTING) {
            reconnecting(mc);
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            if (Config.reconnect && lastServer != null && mc.screen instanceof DisconnectedScreen) {
                state = State.RECONNECTING; // kicked or timed out: join again
                reconnectTimer = 20 * 5;
                reconnectTries = 0;
                joinWait = 0;
            } else {
                state = State.IDLE; // left the world on purpose
            }
            return;
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            lastServer = server;
        }
        if (rtpCooldown > 0) {
            rtpCooldown--;
        }
        if (++pickTimer >= 20) {
            pickTimer = 0;
            int left = Inv.pickaxeLeft(p);
            if (left >= 0) {
                if (left < Config.pickMinDurability) {
                    lowPick = true;
                } else if (Inv.pickaxeFull(p)) {
                    lowPick = false;
                }
            }
        }
        if (p.isDeadOrDying()) {
            stop(mc, "Cycle stopped: you died.");
            return;
        }
        if (mc.screen == null && (state == State.MINING || state == State.STOPPING)) {
            AutoEat.tick(mc, p);
        } else {
            AutoEat.abort(mc);
        }
        ticks++;
        switch (state) {
            case MINING -> mining(mc, p);
            case STOPPING -> stopping(mc);
            case CRAFTING -> crafting(mc);
            case DEPOSITING -> depositing(mc, p);
            case CLEANUP -> cleanup(mc, p);
            case RTP -> rtp(mc);
            default -> { }
        }
    }

    private static void mining(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null) {
            Spread.abort(mc);
            return;
        }
        if (Spread.busy()) {
            Spread.step(mc, p, kind.ore);
            return;
        }
        if (++junkTimer >= 20) {
            junkTimer = 0;
            Inv.dropUnwanted(mc);
        }
        if (++spreadTimer >= 10) {
            spreadTimer = 0;
            Spread.plan(p, kind.ore);
        }
        int ore = p.getInventory().countItem(kind.ore);
        noProgress = ore > lastOre ? 0 : noProgress + 1;
        lastOre = ore;
        if (noProgress >= Config.stallSeconds * 20) {
            startRtp(mc, "No new " + kind.label + " for " + Config.stallSeconds + " seconds", false);
            return;
        }
        if (Config.dangerRtp && rtpCooldown <= 0
                && (p.getHealth() <= Config.dangerHealth || p.isInLava() || p.isOnFire())) {
            startRtp(mc, "In danger (low health, lava or fire)", false);
            return;
        }
        if (kind == OreKind.GOLD && lowPick) {
            // Finish this round early (craft + vault what is held), the next round is coal so mending can repair it.
            Inv.chat(mc, Config.stopCommand);
            Inv.tell(mc, "Pickaxe is below " + Config.pickMinDurability + " durability. Storing what you have, "
                    + "then mining coal so mending repairs it.");
            state = State.STOPPING;
            ticks = 0;
            return;
        }
        if (Inv.fullOfOre(p, kind.ore)) {
            Inv.chat(mc, Config.stopCommand);
            Inv.tell(mc, "Inventory is full of " + kind.label + ". Crafting blocks.");
            state = State.STOPPING;
            ticks = 0;
        }
    }

    private static void stopping(Minecraft mc) {
        if (ticks >= 25) {
            CraftStep.start(mc, kind);
            state = State.CRAFTING;
        }
    }

    private static void crafting(Minecraft mc) {
        Status s = CraftStep.tick(mc);
        if (s == Status.FAILED) {
            stop(mc, "Cycle stopped: " + CraftStep.failure);
        } else if (s == Status.DONE) {
            VaultStep.start(kind);
            state = State.DEPOSITING;
        }
    }

    private static void depositing(Minecraft mc, LocalPlayer p) {
        Status s = VaultStep.tick(mc);
        if (s == Status.FAILED) {
            stop(mc, "Cycle stopped: " + VaultStep.failure);
        } else if (s == Status.DONE) {
            Inv.dropUnwanted(mc); // last purge before the next round, items cannot be picked up again for 2 seconds
            state = State.CLEANUP;
            ticks = 0;
        }
    }

    private static void cleanup(Minecraft mc, LocalPlayer p) {
        if (ticks < 5) {
            return;
        }
        if (Inv.emptySlots(p) < 5) {
            stop(mc, "Cycle stopped: your inventory is still nearly full after storing everything and "
                    + "dropping the unwanted items.");
            return;
        }
        Inv.tell(mc, "Stored " + kind.label + " in " + kind.vaultTitle + ".");
        cycles++;
        kind = kind.other();
        if (kind == OreKind.GOLD && lowPick) {
            kind = OreKind.COAL; // stay on coal until the pickaxe has been mended
        }
        beginMining(mc);
    }

    private static void startRtp(Minecraft mc, String why, boolean verify) {
        verifyRtp = verify;
        rtpRetries = 0;
        if (mc.player != null && mc.level != null) {
            rtpFromX = mc.player.getX();
            rtpFromZ = mc.player.getZ();
            rtpFromDim = mc.level.dimension();
        }
        Inv.chat(mc, Config.stopCommand);
        Spread.abort(mc);
        Inv.tell(mc, why + ": using /" + Config.rtpCommand + ".");
        rtps++;
        rtpCooldown = 20 * 60;
        Rtp.start();
        state = State.RTP;
    }

    private static void rtp(Minecraft mc) {
        Status s = Rtp.tick(mc);
        if (s == Status.RUNNING) {
            return;
        }
        if (!verifyRtp) {
            if (s == Status.FAILED) {
                Inv.tell(mc, "/" + Config.rtpCommand + " problem: " + Rtp.failure);
            }
            beginMining(mc);
            return;
        }
        // After a reconnect: only mine once the teleport really happened (new dimension or far from where we joined).
        boolean moved = false;
        if (s == Status.DONE && mc.player != null && mc.level != null) {
            double dx = mc.player.getX() - rtpFromX;
            double dz = mc.player.getZ() - rtpFromZ;
            moved = mc.level.dimension() != rtpFromDim || dx * dx + dz * dz > 50.0 * 50.0;
        }
        if (moved) {
            verifyRtp = false;
            Inv.tell(mc, "/" + Config.rtpCommand + " worked. Mining.");
            beginMining(mc);
        } else if (++rtpRetries >= 15) {
            stop(mc, "Cycle stopped: /" + Config.rtpCommand + " did not take you out of the lobby after 15 tries. "
                    + "Check rtp_menu_match in config/zenkaiorecycle.properties.");
        } else {
            Inv.tell(mc, "/" + Config.rtpCommand + " has not moved you yet, trying again ("
                    + (s == Status.FAILED ? Rtp.failure : "same place") + ").");
            Rtp.start();
        }
    }

    /** Kicked or disconnected while the loop ran: join the same server again, then carry on mining. */
    private static void reconnecting(Minecraft mc) {
        if (mc.player != null && mc.level != null) {
            if (++joinWait >= 20 * 10) { // give the world a few seconds to load
                joinWait = 0;
                reconnects++;
                Inv.tell(mc, "Reconnected.");
                startRtp(mc, "Back on the server, leaving the lobby", true);
            }
            return;
        }
        if (mc.screen instanceof ConnectScreen) {
            return; // a connection attempt is in progress
        }
        if (--reconnectTimer > 0) {
            return;
        }
        if (++reconnectTries > Config.reconnectMaxTries) {
            state = State.IDLE;
            return;
        }
        reconnectTimer = Math.max(1, Config.reconnectDelaySeconds) * 20;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc,
                ServerAddress.parseString(lastServer.ip), lastServer, false);
    }

    /** Lines for the on-screen panel (only shown while the loop is running). */
    static List<String> hudLines(Minecraft mc) {
        LocalPlayer p = mc.player;
        String what = switch (state) {
            case MINING -> "Mining " + kind.label;
            case STOPPING -> "Stopping, getting ready to craft";
            case CRAFTING -> "Crafting " + kind.label + " blocks";
            case DEPOSITING -> "Storing in " + kind.vaultTitle;
            case CLEANUP -> "Cleaning up";
            case RTP -> "Teleporting (/" + Config.rtpCommand + ")";
            case RECONNECTING -> "Reconnecting";
            default -> "Idle";
        };
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        int left = Inv.pickaxeLeft(p);
        String pick = left < 0 ? "Pickaxe: none in slot 1"
                : "Pickaxe: " + left + " left" + (lowPick ? " (repairing on coal)" : "");
        return List.of(
                "Zenkai Ore Cycle  " + String.format("%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60),
                what,
                kind.label + " ore: " + p.getInventory().countItem(kind.ore) + "   free slots: " + Inv.emptySlots(p),
                state == State.MINING ? "No new ore for " + (noProgress / 20) + "s / " + Config.stallSeconds + "s"
                        : "Round " + (cycles + 1),
                pick,
                "Hunger: " + p.getFoodData().getFoodLevel() + "/20",
                "Rounds done: " + cycles + "   /rtp: " + rtps + "   Reconnects: " + reconnects);
    }
}
