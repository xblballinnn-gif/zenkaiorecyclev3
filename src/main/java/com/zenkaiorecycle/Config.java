package com.zenkaiorecycle;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings you can change without rebuilding: config/zenkaiorecycle.properties (created on first launch). */
final class Config {
    static String vaultCommand = "vault";
    static String craftCommand = "craft";
    static int spareOre = 45;
    static String mineGold = "#mine gold_ore";
    static String mineCoal = "#mine coal_ore";
    static String stopCommand = "#stop";
    /** No new ore for this long: /rtp. */
    static int stallSeconds = 60;
    static String rtpCommand = "rtp";
    /** Text (lower case) that the /rtp menu item for the resource world contains in its name or description. */
    static String rtpMatch = "resource";
    /** Seconds to wait after choosing the world, for the teleport to finish. */
    static int rtpWaitSeconds = 8;
    /** Also /rtp when health is this low (half hearts, 8 = 4 hearts), or in lava / on fire. */
    static boolean dangerRtp = true;
    static int dangerHealth = 8;
    /** Mining gold below this many durability left on the pickaxe: finish the round and mine coal so mending repairs it. */
    static int pickMinDurability = 300;
    static boolean reconnect = true;
    static int reconnectDelaySeconds = 15;
    static int reconnectMaxTries = 40;

    private Config() {
    }

    static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("zenkaiorecycle.properties");
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException ignored) {
                // use defaults
            }
        }
        vaultCommand = p.getProperty("vault_command", vaultCommand).trim();
        if (vaultCommand.startsWith("/")) {
            vaultCommand = vaultCommand.substring(1);
        }
        craftCommand = p.getProperty("craft_command", craftCommand).trim();
        if (craftCommand.startsWith("/")) {
            craftCommand = craftCommand.substring(1);
        }
        mineGold = p.getProperty("mine_gold_command", mineGold).trim();
        mineCoal = p.getProperty("mine_coal_command", mineCoal).trim();
        // An older version saved commands that also mined deepslate ore. Replace those old defaults.
        if (mineGold.equals("#mine gold_ore deepslate_gold_ore")) {
            mineGold = "#mine gold_ore";
        }
        if (mineCoal.equals("#mine coal_ore deepslate_coal_ore")) {
            mineCoal = "#mine coal_ore";
        }
        stopCommand = p.getProperty("stop_command", stopCommand).trim();
        try {
            spareOre = Integer.parseInt(p.getProperty("spare_ore", Integer.toString(spareOre)).trim());
        } catch (NumberFormatException ignored) {
            // keep default
        }

        stallSeconds = num(p, "stall_seconds", stallSeconds);
        rtpCommand = p.getProperty("rtp_command", rtpCommand).trim();
        if (rtpCommand.startsWith("/")) {
            rtpCommand = rtpCommand.substring(1);
        }
        rtpMatch = p.getProperty("rtp_menu_match", rtpMatch).trim().toLowerCase(java.util.Locale.ROOT);
        rtpWaitSeconds = num(p, "rtp_wait_seconds", rtpWaitSeconds);
        dangerRtp = Boolean.parseBoolean(p.getProperty("danger_rtp", Boolean.toString(dangerRtp)).trim());
        dangerHealth = num(p, "danger_health", dangerHealth);
        pickMinDurability = num(p, "pickaxe_min_durability", pickMinDurability);
        reconnect = Boolean.parseBoolean(p.getProperty("reconnect", Boolean.toString(reconnect)).trim());
        reconnectDelaySeconds = num(p, "reconnect_delay_seconds", reconnectDelaySeconds);
        reconnectMaxTries = num(p, "reconnect_max_tries", reconnectMaxTries);

        p.setProperty("vault_command", vaultCommand);
        p.setProperty("craft_command", craftCommand);
        p.setProperty("spare_ore", Integer.toString(spareOre));
        p.setProperty("mine_gold_command", mineGold);
        p.setProperty("mine_coal_command", mineCoal);
        p.setProperty("stop_command", stopCommand);
        p.setProperty("stall_seconds", Integer.toString(stallSeconds));
        p.setProperty("rtp_command", rtpCommand);
        p.setProperty("rtp_menu_match", rtpMatch);
        p.setProperty("rtp_wait_seconds", Integer.toString(rtpWaitSeconds));
        p.setProperty("danger_rtp", Boolean.toString(dangerRtp));
        p.setProperty("danger_health", Integer.toString(dangerHealth));
        p.setProperty("pickaxe_min_durability", Integer.toString(pickMinDurability));
        p.setProperty("reconnect", Boolean.toString(reconnect));
        p.setProperty("reconnect_delay_seconds", Integer.toString(reconnectDelaySeconds));
        p.setProperty("reconnect_max_tries", Integer.toString(reconnectMaxTries));
        try (OutputStream out = Files.newOutputStream(path)) {
            p.store(out, "Zenkai Ore Cycle. spare_ore = ore kept back and stored with the blocks each cycle.");
        } catch (IOException ignored) {
            // not worth failing over
        }
    }

    private static int num(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
