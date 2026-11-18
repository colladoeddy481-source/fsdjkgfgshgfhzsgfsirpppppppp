package com.rickprime.mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod(RickPrimeMod.MODID)
public class RickPrimeMod {
    public static final String MODID = "rickprime";

    public RickPrimeMod() {
    }

    // ================= Modelo de datos =================

    /**
     * Orden en el que se pierden las partes. Primero las piernas (para que el
     * jugador se arrastre), luego los brazos. Si quieres otro orden, solo
     * cambia este arreglo.
     */
    public enum BodyPart {
        RIGHT_LEG, LEFT_LEG, RIGHT_ARM, LEFT_ARM
    }

    private static final BodyPart[] LOSS_ORDER = {
            BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG, BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM
    };

    /** Una extremidad caída en el mundo, puramente visual (no es una Entity de verdad). */
    private static class FallenLimb {
        final BodyPart part;
        final Vec3 worldPos;      // posición donde cayó
        final float yaw;          // rotación con la que quedó tirada
        final ResourceLocation skin;

        boolean returning = false;
        int returnTick = 0;
        static final int RETURN_DURATION = 20; // 1 segundo volando de regreso

        FallenLimb(BodyPart part, Vec3 worldPos, float yaw, ResourceLocation skin) {
            this.part = part;
            this.worldPos = worldPos;
            this.yaw = yaw;
            this.skin = skin;
        }
    }

    /** Estado de desmembramiento del jugador local. Cliente-only (igual que el mod original). */
    private static class BodyState {
        int partsLost = 0;                 // 0..4, índice dentro de LOSS_ORDER
        boolean isDown = false;             // true cuando ya perdió las 4 partes
        boolean regenerating = false;
        float originalMaxHealth = 20.0f;
        final List<FallenLimb> fallenLimbs = new ArrayList<>();

        boolean missing(BodyPart part) {
            for (int i = 0; i < partsLost; i++) {
                if (LOSS_ORDER[i] == part) return true;
            }
            return false;
        }

        boolean bothLegsMissing() {
            return missing(BodyPart.RIGHT_LEG) && missing(BodyPart.LEFT_LEG);
        }
    }

    private static final BodyState STATE = new BodyState();

    private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5a3f9c10-1e2d-4b7a-9f6e-000000000001");
    private static final AttributeModifier CRAWL_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.crawl_speed", -0.85, AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier LIMP_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.limp_speed", -0.40, AttributeModifier.Operation.MULTIPLY_TOTAL);

    // ================= Keybinding =================

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        public static final KeyMapping REGEN_KEY = new KeyMapping(
                "key.rickprime.regen",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "key.categories.rickprime"
        );

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(REGEN_KEY);
        }
    }

    // ================= Lógica de daño, input y tick =================

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ForgeClientEvents {

        @SubscribeEvent
        public static void onPlayerHurt(LivingHurtEvent event) {
            Player player = Minecraft.getInstance().player;
            if (player == null || event.getEntity().getId() != player.getId()) return;

            // Durante la regeneración el jugador es invulnerable.
            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            // Ya está completamente desmembrado: no puede morir, solo sigue tirado.
            if (STATE.isDown) {
                event.setCanceled(true);
                return;
            }

            // Cada golpe: en vez de quitar vida, se pierde la siguiente parte del cuerpo.
            event.setCanceled(true);
            loseNextBodyPart(player);
        }

        private static void loseNextBodyPart(Player player) {
            BodyPart lost = LOSS_ORDER[STATE.partsLost];
            STATE.partsLost++;

            float yaw = player.getYRot() + (player.getRandom().nextFloat() * 60f - 30f);
            ResourceLocation skin = player instanceof AbstractClientPlayer acp
                    ? acp.getSkinTextureLocation()
                    : null;

            // La extremidad cae un poco adelante/alrededor de donde está el jugador.
            Vec3 dropPos = player.position().add(
                    (player.getRandom().nextDouble() - 0.5) * 0.8,
                    0.1,
                    (player.getRandom().nextDouble() - 0.5) * 0.8
            );
            STATE.fallenLimbs.add(new FallenLimb(lost, dropPos, yaw, skin));

            String partName = switch (lost) {
                case RIGHT_LEG -> "la pierna derecha";
                case LEFT_LEG -> "la pierna izquierda";
                case RIGHT_ARM -> "el brazo derecho";
                case LEFT_ARM -> "el brazo izquierdo";
            };
            player.displayClientMessage(Component.literal("§c[Rick Prime] ¡Perdiste " + partName + "!"), true);

            if (STATE.partsLost >= LOSS_ORDER.length) {
                // Ya no le quedan extremidades: queda tirado, con 1 corazón, invulnerable.
                STATE.isDown = true;
                player.setHealth(1.0f);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Has quedado completamente desmembrado! Presiona 'R' para regenerarte."), true);
            }

            applyMovementPenalty(player);
        }

        private static void applyMovementPenalty(Player player) {
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed == null) return;

            speed.removeModifier(SPEED_MODIFIER_ID);

            if (STATE.bothLegsMissing() || STATE.isDown) {
                speed.addTransientModifier(CRAWL_SPEED_MOD);
                player.setPose(Pose.SWIMMING); // reutiliza la animación de arrastre/nado horizontal
            } else if (STATE.missing(BodyPart.RIGHT_LEG) || STATE.missing(BodyPart.LEFT_LEG)) {
                speed.addTransientModifier(LIMP_SPEED_MOD);
            }
        }

        private static void clearMovementPenalty(Player player) {
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(SPEED_MODIFIER_ID);
        }

        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (!ClientModEvents.REGEN_KEY.consumeClick()) return;

            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            if (STATE.partsLost == 0 || STATE.regenerating) return;

            startRegeneration(player);
        }

        private static void startRegeneration(Player player) {
            STATE.regenerating = true;
            for (FallenLimb limb : STATE.fallenLimbs) {
                limb.returning = true;
                limb.returnTick = 0;
            }
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Regenerándote!"), true);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = Minecraft.getInstance().player;
            if (player == null || !STATE.regenerating) return;

            boolean allHome = true;
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.returning) {
                    limb.returnTick++;
                    if (limb.returnTick < FallenLimb.RETURN_DURATION) {
                        allHome = false;
                    }
                }
            }

            // Vida sube gradualmente hasta el máximo original mientras dura la regeneración.
            float healPerTick = (STATE.originalMaxHealth - 1.0f) / FallenLimb.RETURN_DURATION;
            if (player.getHealth() < STATE.originalMaxHealth) {
                player.setHealth(Math.min(STATE.originalMaxHealth, player.getHealth() + healPerTick));
            }

            if (allHome) {
                finishRegeneration(player);
            }
        }

        private static void finishRegeneration(Player player) {
            STATE.regenerating = false;
            STATE.isDown = false;
            STATE.partsLost = 0;
            STATE.fallenLimbs.clear();
            player.setHealth(STATE.originalMaxHealth);
            player.setPose(Pose.STANDING);
            clearMovementPenalty(player);
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Te has regenerado con éxito!"), true);
        }
    }

    // ================= Render de las extremidades caídas =================

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class LimbRenderer {

        // Ajusta estos grados si al probarlo el miembro no queda bien tirado en el suelo.
        private static final float LIE_FLAT_X_ROT = 90f;

        private static PlayerModel<AbstractClientPlayer> model;

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            if (STATE.fallenLimbs.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (model == null) {
                model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            }

            Camera camera = event.getCamera();
            Vec3 camPos = camera.getPosition();
            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.skin == null) continue;
                renderLimb(limb, poseStack, bufferSource, camPos);
            }

            bufferSource.endBatch();
        }

        private static void renderLimb(FallenLimb limb, PoseStack poseStack,
                                        MultiBufferSource.BufferSource bufferSource, Vec3 camPos) {
            ModelPart target = switch (limb.part) {
                case RIGHT_LEG -> model.rightLeg;
                case LEFT_LEG -> model.leftLeg;
                case RIGHT_ARM -> model.rightArm;
                case LEFT_ARM -> model.leftArm;
            };

            model.head.visible = false;
            model.hat.visible = false;
            model.body.visible = false;
            model.rightArm.visible = false;
            model.leftArm.visible = false;
            model.rightLeg.visible = false;
            model.leftLeg.visible = false;
            target.visible = true;

            Vec3 pos = currentPosition(limb);

            poseStack.pushPose();
            poseStack.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(limb.yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(LIE_FLAT_X_ROT));
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0, -1.5, 0); // sube el pivote a la altura aproximada de la cadera

            var buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(limb.skin));
            target.render(poseStack, buffer, 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);

            poseStack.popPose();
        }

        /** Durante el regreso, interpola linealmente desde donde cayó hasta donde debería estar en el jugador. */
        private static Vec3 currentPosition(FallenLimb limb) {
            if (!limb.returning) return limb.worldPos;

            Player player = Minecraft.getInstance().player;
            if (player == null) return limb.worldPos;

            float t = Math.min(1.0f, limb.returnTick / (float) FallenLimb.RETURN_DURATION);
            Vec3 target = player.position().add(0, 1.0, 0); // punto de reinserción aproximado (torso)
            return limb.worldPos.lerp(target, t);
        }
    }
}
