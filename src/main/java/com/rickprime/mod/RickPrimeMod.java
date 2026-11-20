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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Mod(RickPrimeMod.MODID)
public class RickPrimeMod {
    public static final String MODID = "rickprime";

    private static final ResourceLocation DEFAULT_STEVE_SKIN = new ResourceLocation("textures/entity/steve.png");

    private static final int MAX_DRONES = 10;
    private static final int DRONES_PER_SUMMON = 3;
    private static final int DRONE_COOLDOWN_TICKS = 15 * 20;
    private static final int DRONE_LIFETIME_TICKS = 12 * 20;
    private static final float DRONE_UNIFORM_SCALE = 1.0f;

    private static final int RED_BEAM_CHARGE_TICKS = 60 * 20; // 1 minuto para cargar
    private static final int RED_BEAM_VISUAL_TICKS = 8;
    private static final double RED_BEAM_MAX_RANGE = 32.0;

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
        ResourceLocation skin;

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
            this.skin = skin != null ? skin : DEFAULT_STEVE_SKIN;
        }
    }

    private static class PrimeDrone {
        Vec3 pos;
        float yaw;
        final float orbitAngle;
        int ageTicks = 0;
        int attackCooldown = 0;

        PrimeDrone(Vec3 pos, float yaw, float orbitAngle) {
            this.pos = pos;
            this.yaw = yaw;
            this.orbitAngle = orbitAngle;
        }
    }

    private static class RedBeamEffect {
        final Vec3 start;
        final Vec3 end;
        int ticksLeft = RED_BEAM_VISUAL_TICKS;

        RedBeamEffect(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
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
        final List<PrimeDrone> activeDrones = new ArrayList<>();
        final List<RedBeamEffect> activeBeams = new ArrayList<>();

        int droneCooldownTicksLeft = 0;
        int redBeamChargeTicks = 0;

        // Evasión (teletransporte): barra que carga en 3 min, activa dura 1 min.
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

    private static final int EVASION_FULL_CHARGE_TICKS = 3 * 60 * 20;
    private static final int EVASION_DURATION_TICKS = 1 * 60 * 20;

    private static final IGuiOverlay EVASION_BAR_OVERLAY = (gui, guiGraphics, partialTick, width, height) ->
            renderHudBars(guiGraphics, width, height);

    private static void renderHudBars(GuiGraphics guiGraphics, int width, int height) {
        int barWidth = 100;
        int barHeight = 4;
        int x = width / 2 - barWidth / 2;

        // 1) Barra de evasión
        int evasionY = height - 54;
        float evasionPct;
        int evasionColor;
        if (STATE.evasionActive) {
            evasionPct = STATE.evasionActiveTicksLeft / (float) EVASION_DURATION_TICKS;
            evasionColor = 0xFF40E0FF;
        } else {
            evasionPct = STATE.evasionChargeTicks / (float) EVASION_FULL_CHARGE_TICKS;
            evasionColor = 0xFF2266DD;
        }
        evasionPct = Math.max(0f, Math.min(1f, evasionPct));

        guiGraphics.fill(x - 1, evasionY - 1, x + barWidth + 1, evasionY + barHeight + 1, 0x80000000);
        int evasionFilled = (int) (barWidth * evasionPct);
        if (evasionFilled > 0) {
            guiGraphics.fill(x, evasionY, x + evasionFilled, evasionY + barHeight, evasionColor);
        }

        // 2) Barra de robots
        int droneY = height - 62;
        float droneReadyPct;
        int droneColor;
        if (STATE.droneCooldownTicksLeft > 0) {
            droneReadyPct = 1.0f - (STATE.droneCooldownTicksLeft / (float) DRONE_COOLDOWN_TICKS);
            droneColor = 0xFFFFAA00;
        } else {
            droneReadyPct = 1.0f;
            droneColor = 0xFF33EE88;
        }
        droneReadyPct = Math.max(0f, Math.min(1f, droneReadyPct));

        guiGraphics.fill(x - 1, droneY - 1, x + barWidth + 1, droneY + barHeight + 1, 0x80000000);
        int droneFilled = (int) (barWidth * droneReadyPct);
        if (droneFilled > 0) {
            guiGraphics.fill(x, droneY, x + droneFilled, droneY + barHeight, droneColor);
        }

        // 3) Barra de rayo rojo (1 minuto de carga; rojo brillante al estar llena)
        int beamY = height - 70;
        float beamPct = Math.max(0f, Math.min(1f, STATE.redBeamChargeTicks / (float) RED_BEAM_CHARGE_TICKS));
        boolean beamReady = STATE.redBeamChargeTicks >= RED_BEAM_CHARGE_TICKS;
        int beamColor = beamReady ? 0xFFFF2222 : 0xFF991111;

        guiGraphics.fill(x - 1, beamY - 1, x + barWidth + 1, beamY + barHeight + 1, 0x80000000);
        int beamFilled = (int) (barWidth * beamPct);
        if (beamFilled > 0) {
            guiGraphics.fill(x, beamY, x + beamFilled, beamY + barHeight, beamColor);
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

        public static final KeyMapping SUMMON_DRONES_KEY = new KeyMapping(
                "key.rickprime.summon_drones",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "key.categories.rickprime"
        );

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(REGEN_KEY);
            event.register(EVADE_KEY);
            event.register(SUMMON_DRONES_KEY);
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

        private static Player resolveAuthoritative(Player fallback) {
            var integratedServer = Minecraft.getInstance().getSingleplayerServer();
            if (integratedServer != null) {
                ServerPlayer real = integratedServer.getPlayerList().getPlayer(fallback.getUUID());
                if (real != null) return real;
            }
            return fallback;
        }

        private static ResourceLocation resolvePlayerSkin(Player player) {
            if (player instanceof AbstractClientPlayer acp) {
                ResourceLocation loc = acp.getSkinTextureLocation();
                if (loc != null) return loc;
            }
            Player local = Minecraft.getInstance().player;
            if (local instanceof AbstractClientPlayer localAcp && localAcp.getSkinTextureLocation() != null) {
                return localAcp.getSkinTextureLocation();
            }
            return DEFAULT_STEVE_SKIN;
        }

        private static void refreshFallenLimbSkins(Player player) {
            ResourceLocation skin = resolvePlayerSkin(player);
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.skin == null || limb.skin.equals(DEFAULT_STEVE_SKIN)) {
                    limb.skin = skin;
                }
            }
        }

        private static boolean tryFireRedBeam(Player clientPlayer) {
            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                clientPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo derecho está caído! No puedes disparar el rayo."), true);
                return false;
            }
            if (STATE.redBeamChargeTicks < RED_BEAM_CHARGE_TICKS) {
                return false;
            }

            Player realPlayer = resolveAuthoritative(clientPlayer);
            Vec3 eyePos = realPlayer.getEyePosition(1.0f);
            Vec3 lookVec = realPlayer.getLookAngle().normalize();
            Vec3 maxEnd = eyePos.add(lookVec.scale(RED_BEAM_MAX_RANGE));

            BlockHitResult blockHit = realPlayer.level().clip(new ClipContext(
                    eyePos,
                    maxEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    realPlayer
            ));
            double blockDistSq = blockHit.getType() != HitResult.Type.MISS
                    ? eyePos.distanceToSqr(blockHit.getLocation())
                    : RED_BEAM_MAX_RANGE * RED_BEAM_MAX_RANGE;

            AABB searchBox = realPlayer.getBoundingBox().expandTowards(lookVec.scale(RED_BEAM_MAX_RANGE)).inflate(1.2);
            List<Mob> candidateMobs = realPlayer.level().getEntitiesOfClass(
                    Mob.class,
                    searchBox,
                    m -> m != null && m.isAlive() && m.getId() != realPlayer.getId()
            );

            Mob hitMob = null;
            Vec3 hitPos = null;
            double bestDistSq = blockDistSq;

            for (Mob mob : candidateMobs) {
                AABB mobBox = mob.getBoundingBox().inflate(0.25);
                Optional<Vec3> clip = mobBox.clip(eyePos, maxEnd);
                if (clip.isPresent()) {
                    double dSq = eyePos.distanceToSqr(clip.get());
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        hitMob = mob;
                        hitPos = clip.get();
                    }
                }
            }

            Vec3 finalEnd;
            if (hitMob != null && hitPos != null) {
                finalEnd = hitPos;
                applyZoneDamageToMob(realPlayer, hitMob, hitPos);
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                finalEnd = blockHit.getLocation();
            } else {
                finalEnd = maxEnd;
            }

            Vec3 beamOrigin = realPlayer.position().add(lookVec.x * 0.6, 1.35, lookVec.z * 0.6);
            STATE.activeBeams.add(new RedBeamEffect(beamOrigin, finalEnd));
            spawnRedBeamParticles(realPlayer, beamOrigin, finalEnd);

            STATE.redBeamChargeTicks = 0;
            return true;
        }

        private static void applyZoneDamageToMob(Player attacker, Mob targetMob, Vec3 hitPos) {
            double baseY = targetMob.getY();
            double height = Math.max(0.4, targetMob.getBbHeight());
            double relativeHeight = (hitPos.y - baseY) / height;

            if (relativeHeight >= 0.78) {
                targetMob.hurt(attacker.level().damageSources().playerAttack(attacker), targetMob.getMaxHealth() * 100.0f + 999.0f);
                if (targetMob.isAlive()) {
                    targetMob.kill();
                }
                attacker.displayClientMessage(Component.literal("§4[Rayo Rojo] ¡Disparo en la cabeza! Eliminación instantánea."), true);
            } else if (relativeHeight >= 0.35) {
                targetMob.hurt(attacker.level().damageSources().playerAttack(attacker), 12.0f);
                attacker.displayClientMessage(Component.literal("§c[Rayo Rojo] ¡Impacto en el torso! (-6 corazones)"), true);
            } else {
                targetMob.hurt(attacker.level().damageSources().playerAttack(attacker), 5.0f);
                attacker.displayClientMessage(Component.literal("§e[Rayo Rojo] Impacto en las extremidades inferiores. (-2.5 corazones)"), true);
            }
        }

        private static void spawnRedBeamParticles(Player player, Vec3 start, Vec3 end) {
            DustParticleOptions redDust = new DustParticleOptions(new Vector3f(1.0f, 0.08f, 0.08f), 1.4f);
            double dist = start.distanceTo(end);
            int steps = Math.min(80, Math.max(8, (int) (dist * 4.0)));

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double px = start.x + (end.x - start.x) * t;
                double py = start.y + (end.y - start.y) * t;
                double pz = start.z + (end.z - start.z) * t;
                if (player.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(redDust, px, py, pz, 1, 0.02, 0.02, 0.02, 0.0);
                } else {
                    player.level().addParticle(redDust, px, py, pz, 0.0, 0.0, 0.0);
                }
            }
        }

        private static void summonDrones(Player clientPlayer) {
            if (STATE.droneCooldownTicksLeft > 0) {
                float secondsLeft = STATE.droneCooldownTicksLeft / 20.0f;
                clientPlayer.displayClientMessage(Component.literal(
                        "§e[Rick Prime] Espera " + String.format("%.1f", secondsLeft) + "s para invocar robots."), true);
                return;
            }

            int availableSlots = MAX_DRONES - STATE.activeDrones.size();
            if (availableSlots <= 0) {
                clientPlayer.displayClientMessage(Component.literal(
                        "§c[Rick Prime] Ya alcanzaste el límite de " + MAX_DRONES + " robots activos."), true);
                return;
            }

            int toSpawn = Math.min(DRONES_PER_SUMMON, availableSlots);
            for (int i = 0; i < toSpawn; i++) {
                float angle = (float) ((Math.PI * 2.0 * (STATE.activeDrones.size() + 1)) / MAX_DRONES);
                Vec3 spawnPos = clientPlayer.position().add(Math.cos(angle) * 1.6, 1.5, Math.sin(angle) * 1.6);
                STATE.activeDrones.add(new PrimeDrone(spawnPos, clientPlayer.getYRot(), angle));
                spawnTeleportParticles(clientPlayer, spawnPos.x, spawnPos.y, spawnPos.z);
            }

            STATE.droneCooldownTicksLeft = DRONE_COOLDOWN_TICKS;
            clientPlayer.displayClientMessage(Component.literal(
                    "§a[Rick Prime] ¡Invocaste " + toSpawn + " robot(s)! (" + STATE.activeDrones.size() + "/" + MAX_DRONES + " activos)"), true);
        }

        private static void updateDrones(Player clientPlayer) {
            if (STATE.activeDrones.isEmpty()) return;

            Player realPlayer = resolveAuthoritative(clientPlayer);
            List<LivingEntity> nearbyHostiles = realPlayer.level().getEntitiesOfClass(
                    LivingEntity.class,
                    realPlayer.getBoundingBox().inflate(10.0),
                    e -> e != null
                            && e.isAlive()
                            && e.getId() != realPlayer.getId()
                            && !(e instanceof Player)
                            && e instanceof Mob
            );
            nearbyHostiles.sort(Comparator.comparingDouble(e -> e.distanceToSqr(realPlayer)));
            LivingEntity nearestTarget = nearbyHostiles.isEmpty() ? null : nearbyHostiles.get(0);

            List<PrimeDrone> expired = new ArrayList<>();
            for (PrimeDrone drone : STATE.activeDrones) {
                drone.ageTicks++;
                if (drone.attackCooldown > 0) {
                    drone.attackCooldown--;
                }

                float rot = drone.orbitAngle + (drone.ageTicks * 0.10f);
                Vec3 desiredPos = clientPlayer.position().add(
                        Math.cos(rot) * 1.85,
                        1.45 + Math.sin(drone.ageTicks * 0.18) * 0.10,
                        Math.sin(rot) * 1.85
                );

                if (nearestTarget != null) {
                    Vec3 targetPos = nearestTarget.position().add(0.0, nearestTarget.getBbHeight() * 0.65, 0.0);
                    drone.pos = drone.pos.lerp(targetPos, 0.25);
                    double dx = targetPos.x - drone.pos.x;
                    double dz = targetPos.z - drone.pos.z;
                    if (dx * dx + dz * dz > 0.0001) {
                        drone.yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                    }
                    if (drone.pos.distanceTo(targetPos) <= 1.6 && drone.attackCooldown <= 0) {
                        drone.attackCooldown = 25;
                        nearestTarget.hurt(realPlayer.level().damageSources().playerAttack(realPlayer), 4.0f);
                    }
                } else {
                    drone.pos = drone.pos.lerp(desiredPos, 0.25);
                    drone.yaw = clientPlayer.getYRot();
                }

                if (drone.ageTicks >= DRONE_LIFETIME_TICKS) {
                    spawnTeleportParticles(clientPlayer, drone.pos.x, drone.pos.y, drone.pos.z);
                    expired.add(drone);
                }
            }

            STATE.activeDrones.removeAll(expired);
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

            return new Random().nextDouble() < chance;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onLivingDeath(LivingDeathEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();

            event.setCanceled(true);
            clampPlayerToOneHeart(player);
            refreshFallenLimbSkins(player);
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
            refreshFallenLimbSkins(player);

            if (STATE.evasionActive) {
                event.setCanceled(true);
                performEvasionTeleport(player);
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
            refreshFallenLimbSkins(player);

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

            float absorbedDamage = Math.min(currentAbsorption, damage);
            float effectiveDamage = Math.max(0.0f, damage - absorbedDamage);

            if (effectiveDamage <= 0.0f) {
                return;
            }

            if (STATE.isDown || currentHealth <= 2.0f) {
                event.setCanceled(true);
                clampPlayerToOneHeart(player);
                return;
            }

            DamageSource source = event.getSource();
            boolean isExplosion = source.is(DamageTypeTags.IS_EXPLOSION);
            boolean isFall = source.is(DamageTypes.FALL) || source.is(DamageTypeTags.IS_FALL);

            float maxAbsorbableBeforeFloor = Math.max(0.0f, currentHealth - 2.0f);
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

                if (currentHealth - effectiveDamage <= 2.0f) {
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

            if (isFall && (effectiveDamage >= 18.0f || currentHealth - effectiveDamage <= 2.0f)) {
                event.setCanceled(true);
                player.setAbsorptionAmount(0.0f);
                player.setHealth(2.0f);
                processLostHealthLimbs(player, actualTotalLost, false);
                STATE.isDown = true;
                applyMovementPenalty(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Caída severa! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte."), true);
                return;
            }

            if (currentHealth - effectiveDamage <= 2.0f) {
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

            if (tryFireRedBeam(localPlayer)) {
                event.setCanceled(true);
                return;
            }

            if (event.getHand() == InteractionHand.MAIN_HAND && STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo derecho está en el suelo!"), true);
            } else if (event.getHand() == InteractionHand.OFF_HAND && STATE.armDetached(BodyPart.LEFT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡Tu brazo izquierdo está en el suelo!"), true);
            }
        }

        @SubscribeEvent
        public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            tryFireRedBeam(localPlayer);
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
            ResourceLocation skin = resolvePlayerSkin(player);

            Vec3 spawnPos = player.position().add(0, 0.85, 0);
            Vec3 velocity;

            if (flyingOut) {
                double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
                double horizontalSpeed = 0.45 + player.getRandom().nextDouble() * 0.45;
                double verticalSpeed = 0.45 + player.getRandom().nextDouble() * 0.35;
                velocity = new Vec3(Math.cos(angle) * horizontalSpeed, verticalSpeed, Math.sin(angle) * horizontalSpeed);
            } else {
                velocity = new Vec3(
                        (player.getRandom().nextDouble() - 0.5) * 0.14,
                        0.08,
                        (player.getRandom().nextDouble() - 0.5) * 0.14
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

            if (ClientModEvents.SUMMON_DRONES_KEY.consumeClick()) {
                summonDrones(player);
            }
        }

        private static void startRegeneration(Player player) {
            STATE.regenerating = true;
            refreshFallenLimbSkins(player);
            for (FallenLimb limb : STATE.fallenLimbs) {
                limb.returning = true;
                limb.returnStartPos = limb.worldPos;
                limb.returnTick = 0;
            }
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Regenerándote y recuperando extremidades!"), true);
        }

        private static void updateFallenLimbPhysics(Player player, FallenLimb limb) {
            if (limb.returning) return;

            limb.velocity = limb.velocity.add(0.0, -0.05, 0.0);
            double nextX = limb.worldPos.x + limb.velocity.x;
            double nextY = limb.worldPos.y + limb.velocity.y;
            double nextZ = limb.worldPos.z + limb.velocity.z;

            BlockPos belowPos = BlockPos.containing(nextX, nextY - 0.12, nextZ);
            boolean onGround = !player.level().getBlockState(belowPos).isAir() && limb.velocity.y <= 0.0;

            if (onGround) {
                if (limb.velocity.y < -0.18) {
                    limb.velocity = new Vec3(limb.velocity.x * 0.65, -limb.velocity.y * 0.35, limb.velocity.z * 0.65);
                    limb.spinYaw *= 0.6f;
                    limb.spinPitch *= 0.6f;
                } else {
                    limb.velocity = new Vec3(limb.velocity.x * 0.78, 0.0, limb.velocity.z * 0.78);
                    limb.spinYaw *= 0.5f;
                    limb.spinPitch *= 0.5f;
                    if (limb.velocity.horizontalDistanceSqr() < 0.0004) {
                        limb.velocity = Vec3.ZERO;
                        limb.spinYaw = 0f;
                        limb.spinPitch = 0f;
                    }
                    limb.pitch += (90f - limb.pitch) * 0.35f;
                }
            }

            double finalY = onGround && limb.velocity.y <= 0.0
                    ? Math.max(limb.worldPos.y, belowPos.getY() + 1.08)
                    : limb.worldPos.y + limb.velocity.y;

            limb.worldPos = new Vec3(
                    limb.worldPos.x + limb.velocity.x,
                    finalY,
                    limb.worldPos.z + limb.velocity.z
            );
            limb.yaw += limb.spinYaw;
            limb.pitch += limb.spinPitch;
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            serverTickCounter++;
            refreshFallenLimbSkins(player);
            updateDrones(player);

            if (STATE.redBeamChargeTicks < RED_BEAM_CHARGE_TICKS) {
                STATE.redBeamChargeTicks++;
            }

            STATE.activeBeams.removeIf(b -> {
                b.ticksLeft--;
                return b.ticksLeft <= 0;
            });

            if (STATE.droneCooldownTicksLeft > 0) {
                STATE.droneCooldownTicksLeft--;
            }

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
                updateFallenLimbPhysics(player, limb);
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

            if (!STATE.fallenLimbs.isEmpty()) {
                if (model == null) {
                    model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
                }

                for (FallenLimb limb : STATE.fallenLimbs) {
                    ResourceLocation tex = limb.skin != null ? limb.skin : DEFAULT_STEVE_SKIN;
                    renderLimb(limb, tex, poseStack, bufferSource, camPos);
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
                }
            }

            if (player != null && !STATE.activeDrones.isEmpty()) {
                for (PrimeDrone drone : STATE.activeDrones) {
                    renderDrone(drone, poseStack, bufferSource, camPos);
                }
                bufferSource.endBatch(RenderType.translucent());
            }

            if (!STATE.activeBeams.isEmpty()) {
                for (RedBeamEffect beam : STATE.activeBeams) {
                    renderRedBeam(beam, poseStack, bufferSource, camPos);
                }
                bufferSource.endBatch(RenderType.translucent());
            }

            if (player != null && STATE.orangeShieldCooldownTicks > 0) {
                renderOrangeCircularShield(player, poseStack, bufferSource, camPos, event.getPartialTick());
                bufferSource.endBatch(RenderType.translucent());
            }
        }

        private static void renderRedBeam(RedBeamEffect beam, PoseStack poseStack,
                                          MultiBufferSource.BufferSource bufferSource, Vec3 camPos) {
            Vec3 delta = beam.end.subtract(beam.start);
            double length = delta.length();
            if (length < 0.001) return;

            Vec3 dir = delta.normalize();
            float yaw = (float) (Math.atan2(dir.x, dir.z) * (180.0 / Math.PI));
            float pitch = (float) (-Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))) * (180.0 / Math.PI));
            float alpha = Math.min(0.95f, (beam.ticksLeft / (float) RED_BEAM_VISUAL_TICKS) * 0.95f);

            poseStack.pushPose();
            poseStack.translate(beam.start.x - camPos.x, beam.start.y - camPos.y, beam.start.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));

            Matrix4f mat = poseStack.last().pose();
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.translucent());
            float halfW = 0.065f;
            float len = (float) length;

            addBox(consumer, mat, -halfW, -halfW, 0.0f, halfW, halfW, len, 1.0f, 0.08f, 0.08f, alpha);

            poseStack.popPose();
        }

        private static void renderDrone(PrimeDrone drone, PoseStack poseStack,
                                        MultiBufferSource.BufferSource bufferSource, Vec3 camPos) {
            poseStack.pushPose();
            poseStack.translate(drone.pos.x - camPos.x, drone.pos.y - camPos.y, drone.pos.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - drone.yaw));
            poseStack.scale(DRONE_UNIFORM_SCALE, DRONE_UNIFORM_SCALE, DRONE_UNIFORM_SCALE);

            Matrix4f mat = poseStack.last().pose();
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.translucent());

            addBox(consumer, mat, -0.18f, -0.14f, -0.10f,  0.18f,  0.16f, 0.12f, 0.68f, 0.74f, 0.80f, 0.96f);
            addBox(consumer, mat, -0.22f,  0.14f, -0.09f, -0.08f,  0.34f, 0.10f, 0.76f, 0.84f, 0.92f, 0.96f);
            addBox(consumer, mat,  0.08f,  0.14f, -0.09f,  0.22f,  0.34f, 0.10f, 0.76f, 0.84f, 0.92f, 0.96f);
            addBox(consumer, mat, -0.32f, -0.06f, -0.08f, -0.16f,  0.05f, 0.08f, 0.52f, 0.74f, 0.88f, 0.95f);
            addBox(consumer, mat,  0.16f, -0.06f, -0.08f,  0.32f,  0.05f, 0.08f, 0.52f, 0.74f, 0.88f, 0.95f);
            addBox(consumer, mat, -0.24f, -0.25f, -0.08f, -0.10f, -0.14f, 0.08f, 0.45f, 0.72f, 0.86f, 0.95f);
            addBox(consumer, mat,  0.10f, -0.25f, -0.08f,  0.24f, -0.14f, 0.08f, 0.45f, 0.72f, 0.86f, 0.95f);
            addBox(consumer, mat, -0.09f, -0.26f, -0.07f,  0.09f, -0.15f, 0.07f, 0.35f, 0.78f, 0.92f, 0.95f);
            addBox(consumer, mat, -0.04f,  0.09f,  0.11f,  0.04f,  0.17f, 0.15f, 0.35f, 0.88f, 1.00f, 0.98f);
            addBox(consumer, mat, -0.10f, -0.10f,  0.10f,  0.10f,  0.08f, 0.17f, 1.00f, 0.42f, 0.08f, 1.00f);

            poseStack.popPose();
        }

        private static void addBox(VertexConsumer consumer, Matrix4f mat,
                                   float x0, float y0, float z0,
                                   float x1, float y1, float z1,
                                   float r, float g, float b, float a) {
            consumer.vertex(mat, x0, y0, z1).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, 0, 1).endVertex();
            consumer.vertex(mat, x1, y0, z1).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, 0, 1).endVertex();
            consumer.vertex(mat, x1, y1, z1).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, 0, 1).endVertex();
            consumer.vertex(mat, x0, y1, z1).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, 0, 1).endVertex();

            consumer.vertex(mat, x1, y0, z0).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, 0, -1).endVertex();
            consumer.vertex(mat, x0, y0, z0).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, 0, -1).endVertex();
            consumer.vertex(mat, x0, y1, z0).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, 0, -1).endVertex();
            consumer.vertex(mat, x1, y1, z0).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, 0, -1).endVertex();

            consumer.vertex(mat, x0, y1, z1).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
            consumer.vertex(mat, x1, y1, z1).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
            consumer.vertex(mat, x1, y1, z0).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();
            consumer.vertex(mat, x0, y1, z0).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, 1, 0).endVertex();

            consumer.vertex(mat, x0, y0, z0).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(0, -1, 0).endVertex();
            consumer.vertex(mat, x1, y0, z0).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(0, -1, 0).endVertex();
            consumer.vertex(mat, x1, y0, z1).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(0, -1, 0).endVertex();
            consumer.vertex(mat, x0, y0, z1).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(0, -1, 0).endVertex();

            consumer.vertex(mat, x1, y0, z1).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(1, 0, 0).endVertex();
            consumer.vertex(mat, x1, y0, z0).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(1, 0, 0).endVertex();
            consumer.vertex(mat, x1, y1, z0).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(1, 0, 0).endVertex();
            consumer.vertex(mat, x1, y1, z1).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(1, 0, 0).endVertex();

            consumer.vertex(mat, x0, y0, z0).color(r, g, b, a).uv(0, 0).overlayCoords(0).uv2(15728880).normal(-1, 0, 0).endVertex();
            consumer.vertex(mat, x0, y0, z1).color(r, g, b, a).uv(1, 0).overlayCoords(0).uv2(15728880).normal(-1, 0, 0).endVertex();
            consumer.vertex(mat, x0, y1, z1).color(r, g, b, a).uv(1, 1).overlayCoords(0).uv2(15728880).normal(-1, 0, 0).endVertex();
            consumer.vertex(mat, x0, y1, z0).color(r, g, b, a).uv(0, 1).overlayCoords(0).uv2(15728880).normal(-1, 0, 0).endVertex();
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

        private static void renderLimb(FallenLimb limb, ResourceLocation tex, PoseStack poseStack,
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

            var buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(tex));
            target.render(poseStack, buffer, 15728880, OverlayTexture.NO_OVERLAY);
            outerLayer.render(poseStack, buffer, 15728880, OverlayTexture.NO_OVERLAY);

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
