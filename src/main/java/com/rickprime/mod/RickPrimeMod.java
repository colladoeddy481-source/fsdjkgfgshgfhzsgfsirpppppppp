package com.rickprime.mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Mod(RickPrimeMod.MODID)
public class RickPrimeMod {
    public static final String MODID = "rickprime";

    // 20 ticks/s: 2 min activa = 2400 ticks | 3 min recarga = 3600 ticks
    public static final int EVADE_ACTIVE_TICKS = 2400;
    public static final int EVADE_RECHARGE_TICKS = 3600;

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
        int orangeShieldCooldownTicks = 0;
        static final int SHIELD_IDLE_TIMEOUT_TICKS = 40;

        boolean evadeActive = false;
        int evadeRemainingTicks = EVADE_ACTIVE_TICKS;
        int evadeRechargeTicksRemaining = 0;
        int dodgeFlashTicks = 0;
        Vec3 lastTeleportFrom = null;
        Vec3 lastTeleportTo = null;

        final List<FallenLimb> fallenLimbs = new ArrayList<>();

        float evadeChargeRatio() {
            if (evadeActive) {
                return Math.max(0.0f, Math.min(1.0f, evadeRemainingTicks / (float) EVADE_ACTIVE_TICKS));
            }
            if (evadeRechargeTicksRemaining > 0) {
                float chargedTicks = EVADE_RECHARGE_TICKS - evadeRechargeTicksRemaining;
                return Math.max(0.0f, Math.min(1.0f, chargedTicks / (float) EVADE_RECHARGE_TICKS));
            }
            return 1.0f;
        }

        boolean canActivateEvade() {
            return !evadeActive && evadeRechargeTicksRemaining <= 0 && evadeRemainingTicks > 0;
        }

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

        double mobIgnoreChance() {
            if (partsLost <= 1) return 0.0;
            if (partsLost == 2) return 0.35;
            if (partsLost == 3) return 0.70;
            return 1.0;
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

        public static final KeyMapping EVADE_KEY = new KeyMapping(
                "key.rickprime.evade",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.rickprime"
        );

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(REGEN_KEY);
            event.register(EVADE_KEY);
        }

        @SubscribeEvent
        public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.FOOD_LEVEL.id(), "evade_bar", ForgeClientEvents::renderEvadeBar);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ForgeClientEvents {

        private static boolean isLocalPlayer(LivingEntity entity) {
            Player player = Minecraft.getInstance().player;
            return player != null && entity.getId() == player.getId();
        }

        private static Player resolveAuthoritative(Player fallback) {
            var integratedServer = Minecraft.getInstance().getSingleplayerServer();
            if (integratedServer != null) {
                ServerPlayer real = integratedServer.getPlayerList().getPlayer(fallback.getUUID());
                if (real != null) return real;
            }
            return fallback;
        }

        private static boolean isArrowSource(DamageSource source) {
            return source.getDirectEntity() instanceof AbstractArrow || source.is(DamageTypeTags.IS_PROJECTILE);
        }

        private static void refreshOrangeShield(Player player) {
            boolean wasOff = STATE.orangeShieldCooldownTicks <= 0;
            STATE.orangeShieldCooldownTicks = BodyState.SHIELD_IDLE_TIMEOUT_TICKS;
            if (wasOff) {
                player.displayClientMessage(Component.literal("§6[Rick Prime] ¡Esfera naranja activada!"), true);
            }
        }

        private static void clampPlayerToOneHeart(Player player) {
            if (player.getHealth() < 2.0f) {
                player.setHealth(2.0f);
            }
            player.setAbsorptionAmount(0.0f);
            STATE.isDown = true;
            applyMovementPenalty(player);
        }

        private static void teleportEvade(Player player, DamageSource source) {
            Player authPlayer = resolveAuthoritative(player);
            Level level = authPlayer.level();
            Vec3 origin = authPlayer.position();
            Random rand = authPlayer.getRandom();

            double baseAngle = rand.nextDouble() * Math.PI * 2.0;
            Entity attacker = source.getEntity();
            if (attacker != null) {
                double dx = authPlayer.getX() - attacker.getX();
                double dz = authPlayer.getZ() - attacker.getZ();
                double awayAngle = Math.atan2(dz, dx);
                baseAngle = awayAngle + (rand.nextBoolean() ? Math.PI * 0.5 : -Math.PI * 0.5);
            }

            Vec3 chosen = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = baseAngle + (attempt * 0.45);
                double dist = 3.0 + rand.nextDouble() * 3.0;
                double tx = origin.x + Math.cos(angle) * dist;
                double tz = origin.z + Math.sin(angle) * dist;

                int baseY = (int) Math.floor(origin.y);
                for (int dy = 2; dy >= -3; dy--) {
                    int y = baseY + dy;
                    BlockPos feet = BlockPos.containing(tx, y, tz);
                    BlockPos head = feet.above();
                    BlockPos below = feet.below();

                    if (level.getBlockState(feet).isAir()
                            && level.getBlockState(head).isAir()
                            && !level.getBlockState(below).isAir()) {
                        chosen = new Vec3(tx + 0.5, y, tz + 0.5);
                        break;
                    }
                }
                if (chosen != null) break;
            }

            if (chosen == null) {
                chosen = origin.add(
                        Math.cos(baseAngle) * 3.5,
                        0.0,
                        Math.sin(baseAngle) * 3.5
                );
            }

            STATE.lastTeleportFrom = origin;
            STATE.lastTeleportTo = chosen;
            STATE.dodgeFlashTicks = 10;

            authPlayer.teleportTo(chosen.x, chosen.y, chosen.z);
            authPlayer.setDeltaMovement(Vec3.ZERO);
            authPlayer.fallDistance = 0.0f;

            if (player.getId() != authPlayer.getId()) {
                player.teleportTo(chosen.x, chosen.y, chosen.z);
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0.0f;
            }

            player.displayClientMessage(Component.literal("§b[Rick Prime] ¡Esquivado!"), true);
        }

        private static boolean tryEvadeIncomingAttack(Player player, DamageSource source) {
            if (!STATE.evadeActive || STATE.evadeRemainingTicks <= 0) return false;
            if (isArrowSource(source)) return false;

            teleportEvade(player, source);
            return true;
        }

        private static boolean shouldMobStopAttacking(Entity attacker, Player player) {
            if (attacker == null || player == null) return false;
            if (attacker.getId() == player.getId()) return false;

            double chance = STATE.mobIgnoreChance();
            if (chance <= 0.0) return false;
            if (chance >= 1.0) return true;

            long seed = ((long) attacker.getId() * 31L) ^ ((long) STATE.partsLost * 1315423911L);
            double roll = new Random(seed).nextDouble();
            return roll < chance;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onLivingDeath(LivingDeathEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            event.setCanceled(true);
            clampPlayerToOneHeart(player);
            player.displayClientMessage(Component.literal(
                    "§4[Rick Prime] ¡Muerte evitada! Quedaste a 1 corazón. Presiona 'R' para regenerarte."), true);
        }

        @SubscribeEvent
        public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            LivingEntity newTarget = event.getNewTarget();
            if (newTarget == null || newTarget.getId() != player.getId()) return;

            if (shouldMobStopAttacking(event.getEntity(), player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void onLivingAttack(LivingAttackEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            if (isArrowSource(event.getSource())) {
                event.setCanceled(true);
                refreshOrangeShield(player);
                return;
            }

            if (tryEvadeIncomingAttack(player, event.getSource())) {
                event.setCanceled(true);
                return;
            }

            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            Entity attacker = event.getSource().getEntity();
            if (attacker instanceof Mob && shouldMobStopAttacking(attacker, player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onPlayerHurt(LivingHurtEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            if (isArrowSource(event.getSource())) {
                event.setCanceled(true);
                refreshOrangeShield(player);
                return;
            }

            if (tryEvadeIncomingAttack(player, event.getSource())) {
                event.setCanceled(true);
                return;
            }

            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            float currentHealth = player.getHealth();
            float currentAbsorption = player.getAbsorptionAmount();
            float totalPool = currentHealth + currentAbsorption;
            float damage = event.getAmount();

            if (damage <= 0.0f) {
                return;
            }

            if (STATE.isDown || currentHealth <= 2.0f || (currentHealth <= 2.0f && currentAbsorption <= 0.0f)) {
                event.setCanceled(true);
                clampPlayerToOneHeart(player);
                return;
            }

            DamageSource source = event.getSource();
            boolean isExplosion = source.is(DamageTypeTags.IS_EXPLOSION);
            boolean isFall = source.is(DamageTypes.FALL) || source.is(DamageTypeTags.IS_FALL);

            float maxAbsorbableBeforeFloor = Math.max(0.0f, totalPool - 2.0f);
            float actualTotalLost = Math.min(damage, maxAbsorbableBeforeFloor);

            if (isExplosion) {
                float heartsLost = actualTotalLost / 2.0f;
                int limbsToLaunch;
                if (heartsLost >= 8.0f) {
                    limbsToLaunch = 4;
                } else if (heartsLost >= 6.0f) {
                    limbsToLaunch = 3;
                } else {
                    limbsToLaunch = Math.min(LOSS_ORDER.length - STATE.partsLost, (int) Math.floor(heartsLost / 2.0f));
                }

                if (totalPool - damage <= 2.0f) {
                    event.setCanceled(true);
                    player.setAbsorptionAmount(0.0f);
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

            if (isFall && (damage >= 18.0f || totalPool - damage <= 2.0f)) {
                event.setCanceled(true);
                player.setAbsorptionAmount(0.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualTotalLost, damage >= 18.0f);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Caída severa! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte."), true);
                return;
            }

            if (totalPool - damage <= 2.0f) {
                event.setCanceled(true);
                player.setAbsorptionAmount(0.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualTotalLost, false);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Llegaste a 1 corazón! Eres inmortal. Presiona 'R' para regenerarte."), true);
                return;
            }

            processLostHealthLimbs(player, damage, false);
        }

        @SubscribeEvent
        public static void onUseItemFinish(LivingEntityUseItemEvent.Finish event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

            ItemStack stack = event.getItem();
            if (!stack.isEdible() || stack.getFoodProperties(localPlayer) == null) return;

            var foodProps = stack.getFoodProperties(localPlayer);
            FoodData foodData = localPlayer.getFoodData();

            int bonusNutrition = foodProps.getNutrition();
            float bonusSaturation = foodProps.getSaturationModifier();

            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE) || stack.is(Items.GOLDEN_APPLE)) {
                bonusNutrition += 4;
                bonusSaturation += 0.6f;
            }

            foodData.eat(bonusNutrition, bonusSaturation);
            localPlayer.displayClientMessage(Component.literal(
                    "§e[Rick Prime] Barra de comida aumentada (sin regenerar vida ni extremidades)."), true);
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
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

            if (shouldCancelUseItem(localPlayer, localPlayer.getUsedItemHand())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

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
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

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
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

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
            if (!isLocalPlayer(event.getEntity())) return;
            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onAttackEntity(AttackEntityEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();

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
                    case RIGHT_LEG -> "la pierna derecha (Mobs te siguen atacando)";
                    case LEFT_LEG -> "la pierna izquierda (Algunos mobs dejan de atacarte)";
                    case RIGHT_ARM -> "el brazo derecho (70% de los mobs dejan de atacarte)";
                    case LEFT_ARM -> "el brazo izquierdo (Los mobs dejan de atacarte por completo)";
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
            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            if (ClientModEvents.EVADE_KEY.consumeClick()) {
                if (STATE.canActivateEvade()) {
                    STATE.evadeActive = true;
                    STATE.evadeRemainingTicks = EVADE_ACTIVE_TICKS;
                    player.displayClientMessage(Component.literal(
                            "§b[Rick Prime] ¡Evasión por teletransporte activada por 2 minutos!"), true);
                } else if (STATE.evadeActive) {
                    player.displayClientMessage(Component.literal(
                            "§e[Rick Prime] Tu evasión ya está activa."), true);
                } else {
                    int segs = (int) Math.ceil(STATE.evadeRechargeTicksRemaining / 20.0);
                    player.displayClientMessage(Component.literal(
                            "§c[Rick Prime] Barra de evasión cargando (" + segs + "s restantes para 100%)."), true);
                }
            }

            if (ClientModEvents.REGEN_KEY.consumeClick()) {
                if ((STATE.partsLost == 0 && !STATE.isDown && STATE.healthLostAccumulated <= 0.0f) || STATE.regenerating) return;
                startRegeneration(player);
            }
        }

        private static void startRegeneration(Player player) {
            STATE.regenerating = true;
            for (FallenLimb limb : STATE.fallenLimbs) {
                limb.returning = true;
                limb.returnStartPos = limb.worldPos;
                limb.returnTick = 0;
            }
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Regenerándote y recuperando extremidades!"), true);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            if (STATE.dodgeFlashTicks > 0) {
                STATE.dodgeFlashTicks--;
            }

            if (STATE.evadeActive) {
                if (STATE.evadeRemainingTicks > 0) {
                    STATE.evadeRemainingTicks--;
                }
                if (STATE.evadeRemainingTicks <= 0) {
                    STATE.evadeActive = false;
                    STATE.evadeRemainingTicks = 0;
                    STATE.evadeRechargeTicksRemaining = EVADE_RECHARGE_TICKS;
                    player.displayClientMessage(Component.literal(
                            "§e[Rick Prime] Evasión finalizada. La barra tardará 3 minutos en cargarse."), true);
                }
            } else if (STATE.evadeRechargeTicksRemaining > 0) {
                STATE.evadeRechargeTicksRemaining--;
                if (STATE.evadeRechargeTicksRemaining <= 0) {
                    STATE.evadeRechargeTicksRemaining = 0;
                    STATE.evadeRemainingTicks = EVADE_ACTIVE_TICKS;
                    player.displayClientMessage(Component.literal(
                            "§b[Rick Prime] ¡Barra de evasión llena (100%)! Presiona 'V' para usarla."), true);
                }
            }

            if (STATE.orangeShieldCooldownTicks > 0) {
                STATE.orangeShieldCooldownTicks--;
                if (STATE.orangeShieldCooldownTicks == 0) {
                    player.displayClientMessage(Component.literal("§7[Rick Prime] Esfera naranja desactivada."), true);
                }
            }

            for (FallenLimb limb : STATE.fallenLimbs) {
                if (!limb.returning) {
                    if (player.level().getBlockState(BlockPos.containing(limb.worldPos.x, limb.worldPos.y - 0.12, limb.worldPos.z)).isAir()) {
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

            Player realPlayer = resolveAuthoritative(player);
            float healPerTick = (STATE.originalMaxHealth - 2.0f) / FallenLimb.RETURN_DURATION;
            if (realPlayer.getHealth() < STATE.originalMaxHealth) {
                realPlayer.setHealth(Math.min(STATE.originalMaxHealth, realPlayer.getHealth() + healPerTick));
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
            resolveAuthoritative(player).setHealth(STATE.originalMaxHealth);
            player.setPose(Pose.STANDING);
            clearMovementPenalty(player);
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Te has regenerado al 100%!"), true);
        }

        public static void renderEvadeBar(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                                          int screenWidth, int screenHeight) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;

            int barWidth = 100;
            int barHeight = 7;
            int x = (screenWidth / 2) - (barWidth / 2);
            int y = screenHeight - 52;

            float ratio = STATE.evadeChargeRatio();
            int fillWidth = Math.round(barWidth * ratio);

            guiGraphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0xCC000000);
            guiGraphics.fill(x, y, x + barWidth, y + barHeight, 0xFF22252A);

            int fillColor = STATE.evadeActive ? 0xFF00D8FF : (STATE.evadeRechargeTicksRemaining > 0 ? 0xFF3B82F6 : 0xFF22E584);
            if (fillWidth > 0) {
                guiGraphics.fill(x, y, x + fillWidth, y + barHeight, fillColor);
            }

            String label;
            if (STATE.evadeActive) {
                int secs = (int) Math.ceil(STATE.evadeRemainingTicks / 20.0);
                label = "EVASIÓN ACTIVA [V]: " + secs + "s";
            } else if (STATE.evadeRechargeTicksRemaining > 0) {
                int secs = (int) Math.ceil(STATE.evadeRechargeTicksRemaining / 20.0);
                label = "CARGANDO EVASIÓN: " + secs + "s";
            } else {
                label = "EVASIÓN LISTA [V] (2m)";
            }

            int textX = (screenWidth / 2) - (mc.font.width(label) / 2);
            guiGraphics.drawString(mc.font, label, textX, y - 10, 0xFFFFFF, true);
        }

        @SubscribeEvent
        public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
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

            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            Camera camera = event.getCamera();
            Vec3 camPos = camera.getPosition();
            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            if (player != null && STATE.orangeShieldCooldownTicks > 0) {
                renderOrangeCircularShield(player, poseStack, bufferSource, camPos, event.getPartialTick());
            }

            if (player != null && STATE.dodgeFlashTicks > 0) {
                if (STATE.lastTeleportFrom != null) {
                    renderTeleportFlash(STATE.lastTeleportFrom, poseStack, bufferSource, camPos);
                }
                if (STATE.lastTeleportTo != null) {
                    renderTeleportFlash(STATE.lastTeleportTo, poseStack, bufferSource, camPos);
                }
            }

            if (!STATE.fallenLimbs.isEmpty()) {
                if (model == null) {
                    model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
                }

                for (FallenLimb limb : STATE.fallenLimbs) {
                    if (limb.skin == null) continue;
                    renderLimb(limb, poseStack, bufferSource, camPos);
                }
            }

            bufferSource.endBatch();
        }

        private static void renderTeleportFlash(Vec3 pos, PoseStack poseStack,
                                                MultiBufferSource.BufferSource bufferSource,
                                                Vec3 camPos) {
            float alpha = Math.min(0.65f, (STATE.dodgeFlashTicks / 10.0f) * 0.7f);
            float radius = 0.55f + (1.0f - (STATE.dodgeFlashTicks / 10.0f)) * 0.85f;

            poseStack.pushPose();
            poseStack.translate(pos.x - camPos.x, pos.y + 1.0 - camPos.y, pos.z - camPos.z);
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.translucent());

            int segments = 24;
            for (int i = 0; i < segments; i++) {
                double a1 = (2.0 * Math.PI * i) / segments;
                double a2 = (2.0 * Math.PI * (i + 1)) / segments;

                float x1 = (float) (Math.cos(a1) * radius);
                float z1 = (float) (Math.sin(a1) * radius);
                float x2 = (float) (Math.cos(a2) * radius);
                float z2 = (float) (Math.sin(a2) * radius);

                consumer.vertex(mat, x1, -0.65f, z1).color(0.2f, 0.85f, 1.0f, alpha).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                consumer.vertex(mat, x2, -0.65f, z2).color(0.2f, 0.85f, 1.0f, alpha).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                consumer.vertex(mat, x2, 0.65f, z2).color(0.2f, 0.85f, 1.0f, alpha).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                consumer.vertex(mat, x1, 0.65f, z1).color(0.2f, 0.85f, 1.0f, alpha).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
            }

            poseStack.popPose();
        }

        private static void renderOrangeCircularShield(Player player, PoseStack poseStack,
                                                       MultiBufferSource.BufferSource bufferSource,
                                                       Vec3 camPos, float partialTick) {
            double px = player.xo + (player.getX() - player.xo) * partialTick;
            double py = player.yo + (player.getY() - player.yo) * partialTick + 1.0;
            double pz = player.zo + (player.getZ() - player.zo) * partialTick;

            float radius = 1.45f;
            int stacks = 18;
            int slices = 28;
            float alpha = Math.min(0.42f, (STATE.orangeShieldCooldownTicks / (float) BodyState.SHIELD_IDLE_TIMEOUT_TICKS) * 0.50f);

            poseStack.pushPose();
            poseStack.translate(px - camPos.x, py - camPos.y, pz - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees((player.tickCount + partialTick) * 3.0f));

            Matrix4f mat = poseStack.last().pose();
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.translucent());

            for (int i = 0; i < stacks; i++) {
                double phi1 = Math.PI * ((double) i / stacks);
                double phi2 = Math.PI * ((double) (i + 1) / stacks);

                float y1 = (float) (Math.cos(phi1) * radius);
                float r1 = (float) (Math.sin(phi1) * radius);

                float y2 = (float) (Math.cos(phi2) * radius);
                float r2 = (float) (Math.sin(phi2) * radius);

                for (int j = 0; j < slices; j++) {
                    double theta1 = 2.0 * Math.PI * ((double) j / slices);
                    double theta2 = 2.0 * Math.PI * ((double) (j + 1) / slices);

                    float cosT1 = (float) Math.cos(theta1);
                    float sinT1 = (float) Math.sin(theta1);
                    float cosT2 = (float) Math.cos(theta2);
                    float sinT2 = (float) Math.sin(theta2);

                    float x1a = r1 * cosT1;
                    float z1a = r1 * sinT1;
                    float x1b = r1 * cosT2;
                    float z1b = r1 * sinT2;

                    float x2b = r2 * cosT2;
                    float z2b = r2 * sinT2;
                    float x2a = r2 * cosT1;
                    float z2a = r2 * sinT1;

                    consumer.vertex(mat, x1a, y1, z1a).color(1.0f, 0.53f, 0.0f, alpha).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                    consumer.vertex(mat, x1b, y1, z1b).color(1.0f, 0.53f, 0.0f, alpha).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                    consumer.vertex(mat, x2b, y2, z2b).color(1.0f, 0.53f, 0.0f, alpha).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                    consumer.vertex(mat, x2a, y2, z2a).color(1.0f, 0.53f, 0.0f, alpha).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
                }
            }

            poseStack.popPose();
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
