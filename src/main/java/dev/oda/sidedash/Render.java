package dev.oda.sidedash;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.function.Consumer;

/** Renders GUI drawing (items, JEI recipes) into an offscreen framebuffer and returns a PNG. Render thread only. */
final class Render {
    /** @param w,h size in GUI units; scale = output pixels per GUI unit */
    static byte[] png(int w, int h, int scale, Consumer<GuiGraphics> draw) {
        Minecraft mc = Minecraft.getInstance();
        int pw = w * scale, ph = h * scale;
        TextureTarget rt = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSort = RenderSystem.getVertexSorting();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        boolean pushed = false;
        try {
            rt.setClearColor(0, 0, 0, 0);
            rt.clear(Minecraft.ON_OSX);
            rt.bindWrite(true);
            // same setup the game uses for its GUI pass, just sized to our buffer
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            mv.pushMatrix();
            pushed = true;
            mv.translation(0, 0, -11000);
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();

            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw.accept(g);
            g.flush();

            try (NativeImage img = new NativeImage(pw, ph, false)) {
                RenderSystem.bindTexture(rt.getColorTextureId());
                img.downloadTexture(0, false);
                img.flipY();
                return img.asByteArray();
            }
        } catch (Exception e) {
            SideDash.LOG.debug("SideDash: offscreen render failed", e);
            return null;
        } finally {
            if (pushed) { mv.popMatrix(); RenderSystem.applyModelViewMatrix(); }
            RenderSystem.setProjectionMatrix(oldProj, oldSort);
            rt.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }
}
