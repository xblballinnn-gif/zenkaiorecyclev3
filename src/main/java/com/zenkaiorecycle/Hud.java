package com.zenkaiorecycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/** Small status panel in the top-left corner. Only drawn while the loop is running (gone when you stop it). */
@Mod.EventBusSubscriber(modid = ZenkaiOreCycle.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class Hud {
    private Hud() {
    }

    @SubscribeEvent
    public static void onRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!CycleRunner.running() || mc.player == null || mc.options.hideGui || mc.options.renderDebug) {
            return;
        }
        List<String> lines = CycleRunner.hudLines(mc);
        GuiGraphics g = event.getGuiGraphics();
        int width = 0;
        for (String l : lines) {
            width = Math.max(width, mc.font.width(l));
        }
        int x = 6;
        int y = 6;
        int lineH = 10;
        g.fill(x - 4, y - 4, x + width + 4, y + lines.size() * lineH + 2, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(mc.font, lines.get(i), x, y + i * lineH, i == 0 ? 0xFFD700 : 0xFFFFFF, true);
        }
    }
}
