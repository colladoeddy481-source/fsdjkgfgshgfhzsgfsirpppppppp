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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
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
import java.util.Comparator;
import java.util.List;
import java.util.Random;
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

    private static class EvasionReplica {
        Vec3 pos;
        float yaw;
        final int targetEntityId;
        final ResourceLocation skin;
        int ageTicks = 0;
        boolean grabbed = false;
        boolean executedKill = false;
        static final int MAX_LIFETIME_TICKS = 35;
        static final int GRAB_DURATION_TICKS = 10;

        EvasionReplica(Vec3 pos, float yaw, int targetEntityId, ResourceLocation skin) {
            this.pos = pos;
            this.yaw = yaw;
            this.targetEntityId = targetEntityId;
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
        final List<FallenLimb> fallenLimbs = new ArrayList<>();
        final List<EvasionReplica> activeReplicas = new ArrayList<>();

        // Evasión (teletransporte): barra que carga en 3 min, activa dura 2 min.
        int evasionChargeTicks = 0;
        boolean evasionActive = false;
        int evasionActiveTicksLeft = 0;

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
    private static int serverTickCounter = 0;

    private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5a3f9c10-1e2d-4b7a-9f6e-000000000001");
    private static final AttributeModifier CRAWL_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.crawl_speed", -0.85, AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier LIMP_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.limp_speed", -0.40, AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier IMMOBILE_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.immobile_speed", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);

    private static final int EVASION_FULL_CHARGE_TICKS = 3 * 60 * 20; // 3 minutos para cargar
    private static final int EVASION_DURATION_TICKS = 1 * 60 * 20;    // 1 minuto activa

    private static final IGuiOverlay EVASION_BAR_OVERLAY = (gui, guiGraphics, partialTick, width, height) ->
            renderEvasionBar(guiGraphics, width, height);

    private static void renderEvasionBar(GuiGraphics guiGraphics, int width, int height) {
        int barWidth = 100;
        int barHeight = 4;
        int x = width / 2 - barWidth / 2;
        int y = height - 54;

        float pct;
        int color;
        if (STATE.evasionActive) {
            pct = STATE.evasionActiveTicksLeft / (float) EVASION_DURATION_TICKS;
            color = 0xFF40E0FF; // celeste: evasión activa
        } else {
            pct = STATE.evasionChargeTicks / (float) EVASION_FULL_CHARGE_TICKS;
            color = 0xFF2266DD; // azul: cargando
        }
        pct = Math.max(0f, Math.min(1f, pct));

        guiGraphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0x80000000);
        int filled = (int) (barWidth * pct);
        if (filled > 0) {
            guiGraphics.fill(x, y, x + filled, y + barHeight, color);
        }
    }

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
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("rickprime_evasion_bar", EVASION_BAR_OVERLAY);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ForgeClientEvents {

        private static boolean isLocalPlayer(LivingEntity entity) {
            Player player = Minecraft.getInstance().player;
            return player != null && entity.getId() == player.getId();
        }

        /**
         * La vida/hambre son autoritativas del SERVIDOR. En singleplayer, el cliente
         * y el servidor integrado son dos objetos Java distintos aunque representen
         * al mismo jugador: mutar solo el del cliente causa que la vida "no coincida"
         * (el servidor sigue enviando su propio valor real) y, en algunos casos, que
         * el jugador muera de verdad porque el servidor nunca se enteró del clamp.
         * Esta función busca al jugador REAL del servidor integrado para que los
         * cambios de vida/absorción se apliquen donde de verdad importan.
         */
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

        private static boolean shouldMobStopAttacking(Entity attacker, Player player) {
            if (attacker == null || player == null) return false;
            if (attacker.getId() == player.getId()) return false;

            double chance = STATE.mobIgnoreChance();
            if (chance <= 0.0) return false;
            if (chance >= 1.0) return true;

            // Tirada independiente en cada intento (no una semilla fija por mob),
            // para que el % se cumpla de verdad a lo largo del tiempo.
            return new Random().nextDouble() < chance;
        }

        private static void spawnEvasionReplicaForTarget(Player player, Entity preferredAttacker) {
            Entity target = preferredAttacker;
            if (!(target instanceof LivingEntity livingTarget) || !livingTarget.isAlive() || target.getId() == player.getId()) {
                List<LivingEntity> nearbyMobs = player.level().getEntitiesOfClass(
                        LivingEntity.class,
                        player.getBoundingBox().inflate(10.0),
                        e -> e != null
                                && e.isAlive()
                                && e.getId() != player.getId()
                                && !(e instanceof Player)
                                && e instanceof Mob
                );
                nearbyMobs.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
                target = nearbyMobs.isEmpty() ? null : nearbyMobs.get(0);
            }

            if (target == null) return;

            ResourceLocation skin = player instanceof AbstractClientPlayer acp
                    ? acp.getSkinTextureLocation()
                    : null;
            Vec3 spawnPos = player.position();
            spawnTeleportParticles(player, spawnPos.x, spawnPos.y + 1.0, spawnPos.z);
            STATE.activeReplicas.add(new EvasionReplica(spawnPos, player.getYRot(), target.getId(), skin));
        }

        private static void updateEvasionReplicas(Player clientPlayer) {
            if (STATE.activeReplicas.isEmpty()) return;

            Player realPlayer = resolveAuthoritative(clientPlayer);
            List<EvasionReplica> toRemove = new ArrayList<>();

            for (EvasionReplica replica : STATE.activeReplicas) {
                replica.ageTicks++;

                Entity clientEntity = clientPlayer.level().getEntity(replica.targetEntityId);
                Entity serverEntity = realPlayer.level().getEntity(replica.targetEntityId);
                Entity activeTarget = serverEntity != null ? serverEntity : clientEntity;

                if (!(activeTarget instanceof LivingEntity living) || !living.isAlive()) {
                    spawnTeleportParticles(clientPlayer, replica.pos.x, replica.pos.y + 1.0, replica.pos.z);
                    toRemove.add(replica);
                    continue;
                }

                Vec3 targetPos = living.position();
                replica.pos = replica.pos.lerp(targetPos, 0.45);
                double dx = targetPos.x - replica.pos.x;
                double dz = targetPos.z - replica.pos.z;
                if (dx * dx + dz * dz > 0.0001) {
                    replica.yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                }

                if (replica.pos.distanceTo(targetPos) <= 1.35 || replica.ageTicks >= 5) {
                    replica.grabbed = true;
                    living.setDeltaMovement(Vec3.ZERO);
                    living.teleportTo(replica.pos.x, replica.pos.y, replica.pos.z);
                }

                if (replica.grabbed && !replica.executedKill && replica.ageTicks >= EvasionReplica.GRAB_DURATION_TICKS) {
                    replica.executedKill = true;
                    DamageSource killSource = realPlayer.level().damageSources().playerAttack(realPlayer);
                    if (serverEntity instanceof LivingEntity serverLiving && serverLiving.isAlive()) {
                        serverLiving.hurt(killSource, 9999.0f);
                        if (serverLiving.isAlive()) {
                            serverLiving.kill();
                        }
                    }
                    if (clientEntity instanceof LivingEntity clientLiving && clientLiving.isAlive()) {
                        clientLiving.hurt(clientPlayer.level().damageSources().playerAttack(clientPlayer), 9999.0f);
                    }
                    spawnTeleportParticles(clientPlayer, targetPos.x, targetPos.y + 1.0, targetPos.z);
                    toRemove.add(replica);
                    continue;
                }

                if (replica.ageTicks >= EvasionReplica.MAX_LIFETIME_TICKS) {
                    spawnTeleportParticles(clientPlayer, replica.pos.x, replica.pos.y + 1.0, replica.pos.z);
                    toRemove.add(replica);
                }
            }

            STATE.activeReplicas.removeAll(toRemove);
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onLivingDeath(LivingDeathEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity(); // el jugador real que está muriendo, no el del cliente

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
                if (event.getEntity() instanceof Mob mob) {
                    mob.setTarget(null);
                }
            }
        }

        @SubscribeEvent
        public static void onLivingAttack(LivingAttackEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            if (STATE.evasionActive) {
                event.setCanceled(true);
                performEvasionTeleport(player);
                spawnEvasionReplicaForTarget(player, event.getSource().getEntity());
                return;
            }

            if (isArrowSource(event.getSource())) {
                event.setCanceled(true);
                refreshOrangeShield(player);
                return;
            }

            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            Entity attacker = event.getSource().getEntity();
            if (attacker instanceof Mob mob && shouldMobStopAttacking(attacker, player)) {
                event.setCanceled(true);
                mob.setTarget(null);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onPlayerHurt(LivingHurtEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            if (STATE.evasionActive) {
                event.setCanceled(true);
                return;
            }

            if (isArrowSource(event.getSource())) {
                event.setCanceled(true);
                refreshOrangeShield(player);
                return;
            }

            if (STATE.regenerating) {
                event.setCanceled(true);
                return;
            }

            float currentHealth = player.getHealth();
            float currentAbsorption = player.getAbsorptionAmount();
            float damage = event.getAmount();

            if (damage <= 0.0f) {
                return;
            }

            // Primero se consume la vida amarilla (absorción); solo el daño sobrante
            // afecta la vida normal de 10 corazones y cuenta para perder extremidades.
            float absorbedDamage = Math.min(currentAbsorption, damage);
            float effectiveDamage = Math.max(0.0f, damage - absorbedDamage);

            if (effectiveDamage <= 0.0f) {
                return;
            }

            float totalPool = currentHealth;

            if (STATE.isDown || currentHealth <= 2.0f) {
                event.setCanceled(true);
                clampPlayerToOneHeart(player);
                return;
            }

            DamageSource source = event.getSource();
            boolean isExplosion = source.is(DamageTypeTags.IS_EXPLOSION);
            boolean isFall = source.is(DamageTypes.FALL) || source.is(DamageTypeTags.IS_FALL);

            float maxAbsorbableBeforeFloor = Math.max(0.0f, totalPool - 2.0f);
            float actualTotalLost = Math.min(effectiveDamage, maxAbsorbableBeforeFloor);

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

                if (totalPool - effectiveDamage <= 2.0f) {
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

            if (isFall && (effectiveDamage >= 18.0f || totalPool - effectiveDamage <= 2.0f)) {
                event.setCanceled(true);
                player.setAbsorptionAmount(0.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualTotalLost, effectiveDamage >= 18.0f);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Caída severa! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte."), true);
                return;
            }

            if (totalPool - effectiveDamage <= 2.0f) {
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

            processLostHealthLimbs(player, effectiveDamage, false);
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
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

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
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

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
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

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

        // ---------- Evasión: teletransporte corto al recibir un ataque ----------

        private static void performEvasionTeleport(Player clientPlayer) {
            Player real = resolveAuthoritative(clientPlayer);
            Vec3 oldPos = real.position();

            double angle = real.getRandom().nextDouble() * Math.PI * 2.0;
            double dist = 4.0 + real.getRandom().nextDouble() * 2.0;
            double nx = oldPos.x + Math.cos(angle) * dist;
            double nz = oldPos.z + Math.sin(angle) * dist;
            double ny = oldPos.y;

            spawnTeleportParticles(real, oldPos.x, oldPos.y + 1.0, oldPos.z);
            real.teleportTo(nx, ny, nz);
            spawnTeleportParticles(real, nx, ny + 1.0, nz);
            spawnEvasionReplicaForTarget(clientPlayer, null);

            real.displayClientMessage(Component.literal("§b[Rick Prime] ¡Evasión!"), true);
        }

        private static void spawnTeleportParticles(Player player, double x, double y, double z) {
            if (player.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.END_ROD, x, y, z, 30, 0.3, 0.6, 0.3, 0.02);
            } else {
                for (int i = 0; i < 12; i++) {
                    player.level().addParticle(ParticleTypes.END_ROD,
                            x + (player.getRandom().nextDouble() - 0.5) * 0.6,
                            y + (player.getRandom().nextDouble() - 0.5) * 0.6,
                            z + (player.getRandom().nextDouble() - 0.5) * 0.6,
                            0, 0.05, 0);
                }
            }
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

            if (ClientModEvents.REGEN_KEY.consumeClick()) {
                boolean nothingToRegen = STATE.partsLost == 0 && !STATE.isDown && STATE.healthLostAccumulated <= 0.0f;
                if (!nothingToRegen && !STATE.regenerating) {
                    startRegeneration(player);
                }
            }

            if (ClientModEvents.EVADE_KEY.consumeClick()) {
                if (!STATE.evasionActive && STATE.evasionChargeTicks >= EVASION_FULL_CHARGE_TICKS) {
                    STATE.evasionActive = true;
                    STATE.evasionActiveTicksLeft = EVASION_DURATION_TICKS;
                    STATE.evasionChargeTicks = 0;
                    player.displayClientMessage(Component.literal(
                            "§b[Rick Prime] ¡Evasión activada! Esquivarás ataques teletransportándote."), true);
                }
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

            serverTickCounter++;

            updateEvasionReplicas(player);

            if (STATE.evasionActive) {
                STATE.evasionActiveTicksLeft--;
                if (STATE.evasionActiveTicksLeft <= 0) {
                    STATE.evasionActive = false;
                    STATE.evasionActiveTicksLeft = 0;
                }
            } else if (STATE.evasionChargeTicks < EVASION_FULL_CHARGE_TICKS) {
                STATE.evasionChargeTicks++;
            }

            if (STATE.orangeShieldCooldownTicks > 0) {
                STATE.orangeShieldCooldownTicks--;
                if (STATE.orangeShieldCooldownTicks == 0) {
                    player.displayClientMessage(Component.literal("§7[Rick Prime] Esfera naranja desactivada."), true);
                }
            }

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

            if (!STATE.fallenLimbs.isEmpty() || (player != null && !STATE.activeReplicas.isEmpty())) {
                if (model == null) {
                    model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
                }

                if (player != null && !STATE.activeReplicas.isEmpty()) {
                    for (EvasionReplica replica : STATE.activeReplicas) {
                        renderEvasionReplica(replica, poseStack, bufferSource, camPos);
                    }
                }

                for (FallenLimb limb : STATE.fallenLimbs) {
                    if (limb.skin == null) continue;
                    renderLimb(limb, poseStack, bufferSource, camPos);
                }
            }

            bufferSource.endBatch();
        }

        private static void renderEvasionReplica(EvasionReplica replica, PoseStack poseStack,
                                                 MultiBufferSource.BufferSource bufferSource, Vec3 camPos) {
            ResourceLocation tex = replica.skin != null
                    ? replica.skin
                    : new ResourceLocation("textures/entity/steve.png");

            model.head.xRot = 0; model.head.yRot = 0; model.head.zRot = 0;
            model.body.xRot = 0; model.body.yRot = 0; model.body.zRot = 0;
            model.rightArm.xRot = replica.grabbed ? -1.4f : -0.6f;
            model.rightArm.yRot = 0.2f;
            model.rightArm.zRot = 0;
            model.leftArm.xRot = replica.grabbed ? -1.4f : -0.6f;
            model.leftArm.yRot = -0.2f;
            model.leftArm.zRot = 0;
            model.rightLeg.xRot = 0.35f;
            model.rightLeg.yRot = 0;
            model.rightLeg.zRot = 0;
            model.leftLeg.xRot = -0.35f;
            model.leftLeg.yRot = 0;
            model.leftLeg.zRot = 0;

            model.setAllVisible(true);

            poseStack.pushPose();
            poseStack.translate(replica.pos.x - camPos.x, replica.pos.y - camPos.y, replica.pos.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - replica.yaw));
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0, -1.5, 0);

            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(tex));
            int celesteTint = 0xCC40E0FF;
            model.renderToBuffer(poseStack, consumer, 15728880, OverlayTexture.NO_OVERLAY,
                    0.25f, 0.85f, 1.0f, 0.78f);

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
