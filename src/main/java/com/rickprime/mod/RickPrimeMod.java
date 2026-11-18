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
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraftforge.client.event.RenderPlayerEvent;
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

    public enum BodyPart {
        RIGHT_LEG, LEFT_LEG, RIGHT_ARM, LEFT_ARM
    }

    private static final BodyPart[] LOSS_ORDER = {
            BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG, BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM
    };

    private static class FallenLimb {
        final BodyPart part;
        final Vec3 worldPos;
        final float yaw;
        final ResourceLocation skin;

        boolean returning = false;
        int returnTick = 0;
        static final int RETURN_DURATION = 20;

        FallenLimb(BodyPart part, Vec3 worldPos, float yaw, ResourceLocation skin) {
            this.part = part;
            this.worldPos = worldPos;
            this.yaw = yaw;
            this.skin = skin;
        }
    }

    private static class BodyState {
        int partsLost = 0;
        boolean isDown = false;
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

            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            if (STATE.isDown) {
                event.setCanceled(true);
                return;
            }

            float currentHealth = player.getHealth();
            float damage = event.getAmount();

            // Si el jugador ya perdió todas las partes, no puede morir, se queda bloqueado a 1 corazón (2.0f)
            if (STATE.isDown) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                return;
            }

            // Permitimos que el daño baje la vida normalmente, pero si es letal o baja a 1 corazón o menos,
            // lo detenemos exactamente en 1 corazón (2.0f) y pierde una extremidad.
            if (currentHealth - damage <= 2.0f) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                if (STATE.partsLost < LOSS_ORDER.length) {
                    loseNextBodyPart(player);
                }
            } else {
                // Si el golpe no es letal pero aún queremos que pierda extremidades progresivamente por golpes:
                // O si prefieres que pierda extremidades conforme baja la vida:
                // Vamos a hacer que cada golpe le quite una parte y ajuste la vida proporcionalmente o baje con normalidad.
                // Dejemos que el daño baje la vida de forma normal y cada vez que reciba un golpe pierda una extremidad hasta llegar a 1 corazón.
                if (STATE.partsLost < LOSS_ORDER.length) {
                    loseNextBodyPart(player);
                }
            }
        }

        private static void loseNextBodyPart(Player player) {
            BodyPart lost = LOSS_ORDER[STATE.partsLost];
            STATE.partsLost++;

            float yaw = player.getYRot() + (player.getRandom().nextFloat() * 60f - 30f);
            ResourceLocation skin = player instanceof AbstractClientPlayer acp
                    ? acp.getSkinTextureLocation()
                    : null;

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
                STATE.isDown = true;
                player.setHealth(2.0f);
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
                player.setPose(Pose.SWIMMING);
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
            if (player == null) return;

            if (STATE.bothLegsMissing() || STATE.isDown) {
                player.setPose(Pose.SWIMMING);
            }

            if (!STATE.regenerating) return;

            boolean allHome = true;
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.returning) {
                    limb.returnTick++;
                    if (limb.returnTick < FallenLimb.RETURN_DURATION) {
                        allHome = false;
                    }
                }
            }

            float healPerTick = (STATE.originalMaxHealth - 2.0f) / FallenLimb.RETURN_DURATION;
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

    // ================= Ocultar extremidades perdidas del jugador =================

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class PlayerModelHider {

        @SubscribeEvent
        public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;
            if (STATE.partsLost == 0) return;

            PlayerModel<AbstractClientPlayer> playerModel = event.getRenderer().getModel();

            if (STATE.missing(BodyPart.RIGHT_LEG)) {
                playerModel.rightLeg.visible = false;
                playerModel.rightPants.visible = false;
            }
            if (STATE.missing(BodyPart.LEFT_LEG)) {
                playerModel.leftLeg.visible = false;
                playerModel.leftPants.visible = false;
            }
            if (STATE.missing(BodyPart.RIGHT_ARM)) {
                playerModel.rightArm.visible = false;
                playerModel.rightSleeve.visible = false;
            }
            if (STATE.missing(BodyPart.LEFT_ARM)) {
                playerModel.leftArm.visible = false;
                playerModel.leftSleeve.visible = false;
            }
        }

        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            PlayerModel<AbstractClientPlayer> playerModel = event.getRenderer().getModel();
            playerModel.rightLeg.visible = true;
            playerModel.rightPants.visible = true;
            playerModel.leftLeg.visible = true;
            playerModel.leftPants.visible = true;
            playerModel.rightArm.visible = true;
            playerModel.rightSleeve.visible = true;
            playerModel.leftArm.visible = true;
            playerModel.leftSleeve.visible = true;
        }
    }

    // ================= Render de las extremidades caídas =================

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class LimbRenderer {

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

            ModelPart outerLayer = switch (limb.part) {
                case RIGHT_LEG -> model.rightPants;
                case LEFT_LEG -> model.leftPants;
                case RIGHT_ARM -> model.rightSleeve;
                case LEFT_ARM -> model.leftSleeve;
            };

            model.head.visible = false;
            model.hat.visible = false;
            model.body.visible = false;
            model.jacket.visible = false;
            model.rightArm.visible = false;
            model.leftArm.visible = false;
            model.rightLeg.visible = false;
            model.leftLeg.visible = false;
            model.rightSleeve.visible = false;
            model.leftSleeve.visible = false;
            model.rightPants.visible = false;
            model.leftPants.visible = false;

            target.visible = true;
            outerLayer.visible = true;

            Vec3 pos = currentPosition(limb);

            poseStack.pushPose();
            poseStack.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(limb.yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(LIE_FLAT_X_ROT));
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0, -1.5, 0);

            var buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(limb.skin));
            target.render(poseStack, buffer, 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            outerLayer.render(poseStack, buffer, 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);

            poseStack.popPose();
        }

        private static Vec3 currentPosition(FallenLimb limb) {
            if (!limb.returning) return limb.worldPos;

            Player player = Minecraft.getInstance().player;
            if (player == null) return limb.worldPos;

            float t = Math.min(1.0f, limb.returnTick / (float) FallenLimb.RETURN_DURATION);
            Vec3 target = player.position().add(0, 1.0, 0);
            return limb.worldPos.lerp(target, t);
        }
    }
}
