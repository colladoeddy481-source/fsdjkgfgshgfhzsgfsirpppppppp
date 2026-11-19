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
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.HumanoidArm;
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
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod(RickPrimeMod.MODID)
public class RickPrimeMod {
    public static final String MODID = "rickprime";

    public RickPrimeMod() {
    }

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
        float healthLostAccumulated = 0.0f;
        boolean isDown = false;
        boolean isImmobilized = false;
        boolean regenerating = false;
        float originalMaxHealth = 20.0f;
        final List<FallenLimb> fallenLimbs = new ArrayList<>();

        boolean missing(BodyPart part) {
            for (int i = 0; i < partsLost; i++) {
                if (LOSS_ORDER[i] == part) return true;
            }
            return false;
        }

        boolean armDetached(BodyPart arm) {
            if (!missing(arm)) return false;
            if (!regenerating) return true;
            for (FallenLimb limb : fallenLimbs) {
                if (limb.part == arm && limb.returning && limb.returnTick < FallenLimb.RETURN_DURATION) {
                    return true;
                }
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

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ForgeClientEvents {

        @SubscribeEvent
        public static void onPlayerHurt(LivingHurtEvent event) {
            Player player = Minecraft.getInstance().player;
            if (player == null || event.getEntity().getId() != player.getId()) return;

            // Invulnerable durante regeneración
            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            float currentHealth = player.getHealth();
            float damage = event.getAmount();

            // Si el daño es 0 o negativo (por ejemplo bloqueado totalmente por escudo), no hace nada
            if (damage <= 0.0f) {
                return;
            }

            // Si ya está a 1 corazón (2.0f) o abatido, es inmortal: no baja de 1 corazón ni muere
            if (STATE.isDown || currentHealth <= 2.0f) {
                event.setCanceled(true);
                player.setHealth(2.0f);
                STATE.isDown = true;
                applyMovementPenalty(player);
                return;
            }

            DamageSource source = event.getSource();
            boolean isExplosion = source.is(DamageTypeTags.IS_EXPLOSION);
            boolean isFall = source.is(DamageTypes.FALL) || source.is(DamageTypeTags.IS_FALL);

            // Explosiones (Creeper, TNT, etc.): conservan el daño real y la protección del escudo.
            // La cantidad de extremidades que salen volando depende de cuántos corazones quita la explosión:
            // 1 corazón = 2.0f de daño.
            if (isExplosion) {
                float actualDamage = Math.min(damage, Math.max(0.0f, currentHealth - 2.0f));
                float heartsLost = actualDamage / 2.0f;

                int limbsToLaunch;
                if (heartsLost >= 8.0f) {
                    limbsToLaunch = 4; // Muy cerca / 8+ corazones: salen volando las 4 extremidades
                } else if (heartsLost >= 6.0f) {
                    limbsToLaunch = 3; // ~6 corazones: salen volando 3 extremidades
                } else {
                    limbsToLaunch = Math.min(LOSS_ORDER.length - STATE.partsLost, (int) Math.floor(heartsLost / 2.0f));
                }

                if (currentHealth - damage <= 2.0f) {
                    event.setCanceled(true);
                    player.setHealth(2.0f);
                    STATE.isDown = true;
                    if (limbsToLaunch >= 4) {
                        STATE.isImmobilized = true;
                    }
                }

                launchLimbsCount(player, limbsToLaunch, true);
                applyMovementPenalty(player);

                if (STATE.isDown) {
                    String msg = STATE.isImmobilized
                            ? "¡Explosión directa! Salieron volando las 4 extremidades y quedaste inmóvil a 1 corazón. Presiona 'R'."
                            : "¡Explosión! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte.";
                    player.displayClientMessage(Component.literal("§4[Rick Prime] " + msg), true);
                } else if (limbsToLaunch > 0) {
                    player.displayClientMessage(Component.literal(
                            "§c[Rick Prime] ¡La explosión te quitó " + String.format("%.1f", heartsLost)
                                    + " corazones e hizo volar " + limbsToLaunch + " extremidad(es)!"), true);
                }
                return;
            }

            // Caídas de gran altura: si quitaría 9 o 10 corazones (18.0f o 20.0f) o deja <= 2.0f, queda a 1 corazón sin morir.
            if (isFall && (damage >= 18.0f || currentHealth - damage <= 2.0f)) {
                event.setCanceled(true);
                float actualLost = Math.max(0.0f, currentHealth - 2.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualLost, damage >= 18.0f);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Caída severa! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte."), true);
                return;
            }

            // Si el golpe bajaría a 1 corazón (2.0f) o menos
            if (currentHealth - damage <= 2.0f) {
                event.setCanceled(true);
                float actualLost = Math.max(0.0f, currentHealth - 2.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualLost, false);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Llegaste a 1 corazón! Eres inmortal. Presiona 'R' para regenerarte."), true);
                return;
            }

            // Si estaba en 2 corazones (4.0f) y el daño lo baja por debajo de 1 corazón (2.0f), se queda en 1 corazón
            float newHealth = currentHealth - damage;
            if (currentHealth > 2.0f && newHealth < 2.0f) {
                event.setCanceled(true);
                float actualLost = currentHealth - 2.0f;
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualLost, false);
                STATE.isDown = true;
                applyMovementPenalty(player);
                return;
            }

            // Daño normal: la vida baja normalmente y cada 2 corazones perdidos (4.0f) se pierde 1 extremidad
            processLostHealthLimbs(player, damage, false);
        }

        private static void launchLimbsCount(Player player, int count, boolean flyingOut) {
            int launched = 0;
            while (launched < count && STATE.partsLost < LOSS_ORDER.length) {
                loseNextBodyPart(player, flyingOut);
                launched++;
            }
        }

        private static void processLostHealthLimbs(Player player, float lostHealth, boolean heavyFall) {
            if (lostHealth <= 0.0f) return;
            STATE.healthLostAccumulated += lostHealth;

            while (STATE.healthLostAccumulated >= 4.0f && STATE.partsLost < LOSS_ORDER.length) {
                STATE.healthLostAccumulated -= 4.0f;
                loseNextBodyPart(player, heavyFall);
            }
        }

        private static boolean shouldCancelUseItem(Player localPlayer, InteractionHand hand) {
            if (hand == null) return STATE.armDetached(BodyPart.RIGHT_ARM) || STATE.armDetached(BodyPart.LEFT_ARM);
            HumanoidArm mainArm = localPlayer.getMainArm();
            boolean usingRight = (hand == InteractionHand.MAIN_HAND && mainArm == HumanoidArm.RIGHT)
                    || (hand == InteractionHand.OFF_HAND && mainArm == HumanoidArm.LEFT);
            return usingRight ? STATE.armDetached(BodyPart.RIGHT_ARM) : STATE.armDetached(BodyPart.LEFT_ARM);
        }

        @SubscribeEvent
        public static void onUseItemStart(LivingEntityUseItemEvent.Start event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (shouldCancelUseItem(localPlayer, localPlayer.getUsedItemHand())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (shouldCancelUseItem(localPlayer, localPlayer.getUsedItemHand())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            HumanoidArm mainArm = localPlayer.getMainArm();
            boolean isRightHand = (event.getHand() == InteractionHand.MAIN_HAND && mainArm == HumanoidArm.RIGHT)
                    || (event.getHand() == InteractionHand.OFF_HAND && mainArm == HumanoidArm.LEFT);

            if (isRightHand && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
            } else if (!isRightHand && STATE.armDetached(BodyPart.LEFT_ARM)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (event.getHand() == InteractionHand.MAIN_HAND && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo derecho está en el suelo!"), true);
            } else if (event.getHand() == InteractionHand.OFF_HAND && STATE.armDetached(BodyPart.LEFT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo izquierdo está en el suelo!"), true);
            }
        }

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (event.getHand() == InteractionHand.MAIN_HAND && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo derecho está en el suelo!"), true);
            } else if (event.getHand() == InteractionHand.OFF_HAND && STATE.armDetached(BodyPart.LEFT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo izquierdo está en el suelo!"), true);
            }
        }

        @SubscribeEvent
        public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onAttackEntity(AttackEntityEvent event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡No tienes el brazo derecho en tu cuerpo para atacar!"), true);
            }
        }

        @SubscribeEvent
        public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            if (event.isAttack() && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }

            if (event.isUseItem()) {
                if (event.getHand() == InteractionHand.MAIN_HAND && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                    event.setCanceled(true);
                    event.setSwingHand(false);
                } else if (event.getHand() == InteractionHand.OFF_HAND && STATE.armDetached(BodyPart.LEFT_ARM)) {
                    event.setCanceled(true);
                    event.setSwingHand(false);
                }
            }
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
                double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
                double horizontalSpeed = 0.45 + player.getRandom().nextDouble() * 0.45;
                double verticalSpeed = 0.45 + player.getRandom().nextDouble() * 0.35;
                velocity = new Vec3(Math.cos(angle) * horizontalSpeed, verticalSpeed, Math.sin(angle) * horizontalSpeed);
            } else {
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
                    case RIGHT_LEG -> "la pierna derecha (2 corazones perdidos)";
                    case LEFT_LEG -> "la pierna izquierda (4 corazones perdidos)";
                    case RIGHT_ARM -> "el brazo derecho (6 corazones perdidos · Mano principal bloqueada)";
                    case LEFT_ARM -> "el brazo izquierdo (8 corazones perdidos · Mano secundaria bloqueada)";
                };
                player.displayClientMessage(Component.literal("§c[Rick Prime] ¡Perdiste " + partName + "!"), true);
            }
            applyMovementPenalty(player);
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

            if ((STATE.partsLost == 0 && !STATE.isDown && STATE.healthLostAccumulated <= 0.0f) || STATE.regenerating) return;

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

            for (FallenLimb limb : STATE.fallenLimbs) {
                if (!limb.returning) {
                    if (player.level().getBlockState(net.minecraft.core.BlockPos.containing(limb.worldPos.x, limb.worldPos.y - 0.12, limb.worldPos.z)).isAir()) {
                        limb.velocity = limb.velocity.add(0, -0.05, 0);
                        limb.worldPos = limb.worldPos.add(limb.velocity);
                        limb.yaw += limb.spinYaw;
                        limb.pitch += limb.spinPitch;
                    } else {
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
            STATE.healthLostAccumulated = 0.0f;
            STATE.fallenLimbs.clear();
            player.setHealth(STATE.originalMaxHealth);
            player.setPose(Pose.STANDING);
            clearMovementPenalty(player);
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Te has regenerado al 100%!"), true);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class PlayerModelHider {

        private static final List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> removedItemLayers = new ArrayList<>();
        private static Field layersField;

        @SuppressWarnings("unchecked")
        private static List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> getLayersList(
                LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
            try {
                if (layersField == null) {
                    for (Field f : LivingEntityRenderer.class.getDeclaredFields()) {
                        if (List.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            layersField = f;
                            break;
                        }
                    }
                }
                if (layersField != null) {
                    return (List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>>) layersField.get(renderer);
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        @SubscribeEvent
        public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            PlayerModel<AbstractClientPlayer> playerModel = event.getRenderer().getModel();

            boolean rightLegOut = STATE.missing(BodyPart.RIGHT_LEG) && (!STATE.regenerating || isPartReturning(BodyPart.RIGHT_LEG));
            boolean leftLegOut  = STATE.missing(BodyPart.LEFT_LEG)  && (!STATE.regenerating || isPartReturning(BodyPart.LEFT_LEG));
            boolean rightArmOut = STATE.armDetached(BodyPart.RIGHT_ARM);
            boolean leftArmOut  = STATE.armDetached(BodyPart.LEFT_ARM);

            if (rightLegOut) {
                playerModel.rightLeg.visible = false;
                playerModel.rightPants.visible = false;
            }
            if (leftLegOut) {
                playerModel.leftLeg.visible = false;
                playerModel.leftPants.visible = false;
            }
            if (rightArmOut) {
                playerModel.rightArm.visible = false;
                playerModel.rightSleeve.visible = false;
            }
            if (leftArmOut) {
                playerModel.leftArm.visible = false;
                playerModel.leftSleeve.visible = false;
            }

            if (rightArmOut || leftArmOut) {
                List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layers = getLayersList(event.getRenderer());
                if (layers != null && removedItemLayers.isEmpty()) {
                    for (RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> layer : layers) {
                        if (layer instanceof ItemInHandLayer) {
                            removedItemLayers.add(layer);
                        }
                    }
                    layers.removeAll(removedItemLayers);
                }
            }
        }

        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || event.getEntity().getId() != localPlayer.getId()) return;

            if (!removedItemLayers.isEmpty()) {
                List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layers = getLayersList(event.getRenderer());
                if (layers != null) {
                    layers.addAll(removedItemLayers);
                }
                removedItemLayers.clear();
            }

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

        private static boolean isPartReturning(BodyPart part) {
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.part == part) {
                    return limb.returning && limb.returnTick < FallenLimb.RETURN_DURATION;
                }
            }
            return false;
        }
    }

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

            model.head.xRot = 0; model.head.yRot = 0; model.head.zRot = 0;
            model.body.xRot = 0; model.body.yRot = 0; model.body.zRot = 0;
            model.rightArm.xRot = 0; model.rightArm.yRot = 0; model.rightArm.zRot = 0;
            model.leftArm.xRot = 0; model.leftArm.yRot = 0; model.leftArm.zRot = 0;
            model.rightLeg.xRot = 0; model.rightLeg.yRot = 0; model.rightLeg.zRot = 0;
            model.leftLeg.xRot = 0; model.leftLeg.yRot = 0; model.leftLeg.zRot = 0;

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
