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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
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
        Vec3 worldPos;
        Vec3 velocity;
        float yaw;
        float pitch;
        float spinYaw;
        float spinPitch;
        final ResourceLocation skin;

        boolean returning = false;
        Vec3 returnStartPos;
        int returnTick = 0;
        static final int RETURN_DURATION = 20;

        FallenLimb(BodyPart part, Vec3 worldPos, Vec3 velocity, float yaw, ResourceLocation skin) {
            this.part = part;
            this.worldPos = worldPos;
            this.velocity = velocity;
            this.yaw = yaw;
            this.pitch = 90f;
            this.spinYaw = 0f;
            this.spinPitch = 0f;
            this.skin = skin;
        }
    }

    private static class BodyState {
        int partsLost = 0;
        boolean isDown = false;
        boolean isImmobilized = false; // Inmovilizado tras explosión o caída de gran altura
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
    private static final AttributeModifier IMMOBILE_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.immobile_speed", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);

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

            if (STATE.isDown || STATE.isImmobilized) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                return;
            }

            DamageSource source = event.getSource();
            float damage = event.getAmount();
            float currentHealth = player.getHealth();

            boolean isExplosion = source.is(DamageTypeTags.IS_EXPLOSION);
            // Caída de gran altura: daño de caída de 8.0 o más (equivale a ~11 bloques o más de caída)
            boolean isHighFall = (source.is(DamageTypes.FALL) || source.is(DamageTypeTags.IS_FALL)) && damage >= 8.0f;

            if (isExplosion || isHighFall) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                shatterAllRemainingParts(player, isExplosion);
                return;
            }

            // Daño normal (golpes, flechas, etc.)
            if (currentHealth - damage <= 2.0f) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                if (STATE.partsLost < LOSS_ORDER.length) {
                    loseNextBodyPart(player, false);
                }
            } else {
                if (STATE.partsLost < LOSS_ORDER.length) {
                    loseNextBodyPart(player, false);
                }
            }
        }

        private static void shatterAllRemainingParts(Player player, boolean isExplosion) {
            while (STATE.partsLost < LOSS_ORDER.length) {
                loseNextBodyPart(player, true);
            }
            STATE.isDown = true;
            STATE.isImmobilized = true;
            player.setHealth(2.0f);
            applyMovementPenalty(player);

            String reason = isExplosion ? "¡Una explosión te ha hecho volar en pedazos!" : "¡La caída te ha destrozado!";
            player.displayClientMessage(Component.literal(
                    "§4[Rick Prime] " + reason + " Estás inmóvil. Presiona 'R' para regenerarte."), true);
        }

        private static void loseNextBodyPart(Player player, boolean flyingOut) {
            BodyPart lost = LOSS_ORDER[STATE.partsLost];
            STATE.partsLost++;

            float yaw = player.getYRot() + (player.getRandom().nextFloat() * 120f - 60f);
            ResourceLocation skin = player instanceof AbstractClientPlayer acp
                    ? acp.getSkinTextureLocation()
                    : null;

            Vec3 spawnPos = player.position().add(0, 0.8, 0);
            Vec3 velocity;

            if (flyingOut) {
                // Impulso fuerte hacia afuera y arriba para salir volando por explosión o caída fuerte
                double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
                double horizontalSpeed = 0.45 + player.getRandom().nextDouble() * 0.45;
                double verticalSpeed = 0.45 + player.getRandom().nextDouble() * 0.35;
                velocity = new Vec3(Math.cos(angle) * horizontalSpeed, verticalSpeed, Math.sin(angle) * horizontalSpeed);
            } else {
                // Desprendimiento normal cerca del jugador
                velocity = new Vec3(
                        (player.getRandom().nextDouble() - 0.5) * 0.18,
                        0.15,
                        (player.getRandom().nextDouble() - 0.5) * 0.18
                );
            }

            FallenLimb limb = new FallenLimb(lost, spawnPos, velocity, yaw, skin);
            if (flyingOut) {
                limb.spinYaw = (player.getRandom().nextFloat() - 0.5f) * 35f;
                limb.spinPitch = (player.getRandom().nextFloat() - 0.5f) * 35f;
            }
            STATE.fallenLimbs.add(limb);

            if (!flyingOut) {
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
        }

        private static void applyMovementPenalty(Player player) {
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed == null) return;

            speed.removeModifier(SPEED_MODIFIER_ID);

            if (STATE.isImmobilized) {
                speed.addTransientModifier(IMMOBILE_SPEED_MOD);
                player.setPose(Pose.SWIMMING);
            } else if (STATE.bothLegsMissing() || STATE.isDown) {
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

        // Bloquea cualquier intento de caminar, saltar o agacharse mientras está inmóvil
        @SubscribeEvent
        public static void onMovementInput(MovementInputUpdateEvent event) {
            if (STATE.isImmobilized && !STATE.regenerating) {
                event.getInput().forwardImpulse = 0f;
                event.getInput().leftImpulse = 0f;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
            }
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
                limb.returnStartPos = limb.worldPos;
                limb.returnTick = 0;
            }
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Regenerándote!"), true);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            // Física simple de vuelo y caída al suelo para las extremidades desprendidas
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (!limb.returning) {
                    double groundY = Math.floor(limb.worldPos.y) + 0.1;
                    // Comprobar si hay bloque sólido debajo
                    if (player.level().getBlockState(net.minecraft.core.BlockPos.containing(limb.worldPos.x, limb.worldPos.y - 0.12, limb.worldPos.z)).isAir()) {
                        limb.velocity = limb.velocity.add(0, -0.05, 0); // Gravedad
                        limb.worldPos = limb.worldPos.add(limb.velocity);
                        limb.yaw += limb.spinYaw;
                        limb.pitch += limb.spinPitch;
                    } else {
                        // Al tocar el suelo se detiene y queda acostada
                        limb.velocity = Vec3.ZERO;
                        limb.spinYaw = 0f;
                        limb.spinPitch = 0f;
                        limb.pitch = 90f;
                    }
                }
            }

            if (STATE.isImmobilized) {
                player.setDeltaMovement(0, Math.min(0, player.getDeltaMovement().y), 0);
                player.setPose(Pose.SWIMMING);
            } else if (STATE.bothLegsMissing() || STATE.isDown) {
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
            STATE.isImmobilized = false;
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
            poseStack.mulPose(Axis.XP.rotationDegrees(limb.pitch));
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
            Vec3 start = limb.returnStartPos != null ? limb.returnStartPos : limb.worldPos;
            Vec3 target = player.position().add(0, 1.0, 0);
            return start.lerp(target, t);
        }
    }
}
