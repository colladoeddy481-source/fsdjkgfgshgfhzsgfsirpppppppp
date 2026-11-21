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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Mod(RickPrimeMod.MODID)
public class RickPrimeMod {
    public static final String MODID = "rickprime";

    private static final ResourceLocation DEFAULT_STEVE_SKIN = new ResourceLocation("textures/entity/steve.png");
    private static final ResourceLocation RICK_PRIME_SKIN = new ResourceLocation(MODID, "textures/entity/rick_prime.png");

    private static final int MAX_DRONES = 10;
    private static final int DRONES_PER_SUMMON = 1;
    private static final int DRONE_COOLDOWN_TICKS = 10 * 20;
    private static final int DRONE_IDLE_TIMEOUT_TICKS = 2 * 60 * 20;
    private static final float DRONE_UNIFORM_SCALE = 1.0f;
    private static final double DRONE_SEARCH_HALF_EXTENT_XZ = 16.0;
    private static final double DRONE_SEARCH_HALF_EXTENT_Y = 16.0;

    private static final int RED_BEAM_CHARGE_TICKS = 60 * 20;
    private static final int RED_BEAM_VISUAL_TICKS = 8;
    private static final double RED_BEAM_MAX_RANGE = 32.0;

    private static final int AFTERIMAGE_MAX_AGE = 14;

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
        final int slotIndex;
        int ageTicks = 0;
        int ticksWithoutEnemy = 0;
        int attackCooldown = 0;
        Vec3 lockedAimPoint = null;

        PrimeDrone(Vec3 pos, float yaw, int slotIndex) {
            this.pos = pos;
            this.yaw = yaw;
            this.slotIndex = slotIndex;
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

    private static class EvasionAfterimage {
        final Vec3 pos;
        final float yaw;
        int age = 0;

        EvasionAfterimage(Vec3 pos, float yaw) {
            this.pos = pos;
            this.yaw = yaw;
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
        final List<EvasionAfterimage> afterimages = new ArrayList<>();

        int droneCooldownTicksLeft = 0;
        int redBeamChargeTicks = 0;

        int evasionChargeTicks = 0;
        boolean evasionActive = false;
        int evasionActiveTicksLeft = 0;

        void reset() {
            partsLost = 0;
            healthLostAccumulated = 0.0f;
            isDown = false;
            isImmobilized = false;
            regenerating = false;
            originalMaxHealth = 20.0f;
            orangeShieldCooldownTicks = 0;
            fallenLimbs.clear();
            activeDrones.clear();
            activeBeams.clear();
            afterimages.clear();
            droneCooldownTicksLeft = 0;
            redBeamChargeTicks = 0;
            evasionChargeTicks = 0;
            evasionActive = false;
            evasionActiveTicksLeft = 0;
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
    private static int clientTickCounter = 0;

    private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5a3f9c10-1e2d-4b7a-9f6e-000000000001");
    private static final AttributeModifier CRAWL_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.crawl_speed", -0.85, AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier LIMP_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.limp_speed", -0.40, AttributeModifier.Operation.MULTIPLY_TOTAL);
    private static final AttributeModifier IMMOBILE_SPEED_MOD =
            new AttributeModifier(SPEED_MODIFIER_ID, "rickprime.immobile_speed", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);

    private static final int EVASION_FULL_CHARGE_TICKS = 3 * 60 * 20;
    private static final int EVASION_DURATION_TICKS = 30 * 20;

    private static final IGuiOverlay EVASION_BAR_OVERLAY = (gui, guiGraphics, partialTick, width, height) ->
            renderHudBars(guiGraphics, width, height);

    private static void renderHudBars(GuiGraphics guiGraphics, int width, int height) {
        int barWidth = 100;
        int barHeight = 4;
        int x = width - barWidth - 8;

        int beamY = height - 26;
        float beamPct = Math.max(0f, Math.min(1f, STATE.redBeamChargeTicks / (float) RED_BEAM_CHARGE_TICKS));
        boolean beamReady = STATE.redBeamChargeTicks >= RED_BEAM_CHARGE_TICKS;
        int beamColor = beamReady ? 0xFFFF2222 : 0xFF991111;

        guiGraphics.fill(x - 1, beamY - 1, x + barWidth + 1, beamY + barHeight + 1, 0x80000000);
        int beamFilled = (int) (barWidth * beamPct);
        if (beamFilled > 0) {
            guiGraphics.fill(x, beamY, x + beamFilled, beamY + barHeight, beamColor);
        }

        int droneY = height - 18;
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

        int evasionY = height - 10;
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

        @SubscribeEvent
        public static void onPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            Player player = event.getPlayer();
            STATE.reset();
            clientTickCounter = 0;
            if (player != null) {
                clearMovementPenalty(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
            STATE.reset();
            clientTickCounter = 0;
            Player newPlayer = event.getNewPlayer();
            if (newPlayer != null) {
                clearMovementPenalty(newPlayer);
                newPlayer.setPose(Pose.STANDING);
            }
        }

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

        private static void runOnServerOrNow(Player reference, Runnable action) {
            var integratedServer = Minecraft.getInstance().getSingleplayerServer();
            if (integratedServer != null && reference != null && reference.level().isClientSide) {
                integratedServer.execute(action);
            } else {
                action.run();
            }
        }

        private static void runOnClientOrNow(Runnable action) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                action.run();
            } else {
                mc.execute(action);
            }
        }

        private static int findFreeDroneSlot() {
            Set<Integer> used = new HashSet<>();
            for (PrimeDrone d : STATE.activeDrones) {
                used.add(d.slotIndex);
            }
            for (int slot = 0; slot < MAX_DRONES; slot++) {
                if (!used.contains(slot)) {
                    return slot;
                }
            }
            return 0;
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

        private static ResourceLocation resolveRickPrimeSkin(Player fallbackPlayer) {
            var resourceOpt = Minecraft.getInstance().getResourceManager().getResource(RICK_PRIME_SKIN);
            if (resourceOpt.isPresent()) {
                return RICK_PRIME_SKIN;
            }
            return resolvePlayerSkin(fallbackPlayer);
        }

        private static void refreshFallenLimbSkins(Player player) {
            ResourceLocation skin = resolvePlayerSkin(player);
            for (FallenLimb limb : STATE.fallenLimbs) {
                if (limb.skin == null || limb.skin.equals(DEFAULT_STEVE_SKIN)) {
                    limb.skin = skin;
                }
            }
        }

        private static boolean isHostileTarget(LivingEntity entity, Player player) {
            if (entity == null || !entity.isAlive() || entity.isRemoved() || entity.getId() == player.getId() || entity instanceof Player) {
                return false;
            }
            if (entity.level().dimension() != player.level().dimension()) {
                return false;
            }
            if (entity instanceof Enemy) return true;
            if (entity instanceof Mob mob) {
                LivingEntity target = mob.getTarget();
                return target != null && target.getId() == player.getId();
            }
            return false;
        }

        private static boolean hasLineOfSight(Level level, Vec3 start, Vec3 end, Entity ignoreEntity) {
            BlockHitResult hit = level.clip(new ClipContext(
                    start,
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    ignoreEntity
            ));
            return hit.getType() == HitResult.Type.MISS;
        }

        private static void spawnAfterimage(Vec3 pos, float yaw) {
            if (STATE.afterimages.size() >= 8) {
                STATE.afterimages.remove(0);
            }
            STATE.afterimages.add(new EvasionAfterimage(pos, yaw));
        }

        private static void updateEvasionAfterimages(Player clientPlayer) {
            STATE.afterimages.removeIf(img -> {
                img.age++;
                return img.age >= AFTERIMAGE_MAX_AGE;
            });

            if (STATE.evasionActive && clientPlayer.getDeltaMovement().horizontalDistanceSqr() > 0.002 && clientTickCounter % 3 == 0) {
                spawnAfterimage(clientPlayer.position(), clientPlayer.getYRot());
            }
        }

        private static boolean tryFireRedBeam(Player clientPlayer) {
            if (!clientPlayer.isAlive()) return false;
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
                    ClipContext.Block.COLLIDER,
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
                    m -> m != null && m.isAlive() && !m.isRemoved() && m.getId() != realPlayer.getId()
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
                applyZoneDamageToMob(clientPlayer, realPlayer, hitMob, hitPos, "Rayo Rojo");
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                finalEnd = blockHit.getLocation();
            } else {
                finalEnd = maxEnd;
            }

            Vec3 beamOrigin = eyePos.add(lookVec.scale(0.45)).subtract(0.0, 0.2, 0.0);
            STATE.activeBeams.add(new RedBeamEffect(beamOrigin, finalEnd));
            spawnRedBeamParticles(realPlayer, beamOrigin, finalEnd);

            STATE.redBeamChargeTicks = 0;
            return true;
        }

        private static void applyZoneDamageToMob(Player feedbackPlayer, Player attacker, LivingEntity targetEntity, Vec3 hitPos, String sourceLabel) {
            if (targetEntity == null || !targetEntity.isAlive() || targetEntity.isRemoved()) return;

            double baseY = targetEntity.getY();
            double height = Math.max(0.4, targetEntity.getBbHeight());
            double relativeHeight = (hitPos.y - baseY) / height;

            String message;
            if (relativeHeight >= 0.78) {
                message = "§4[" + sourceLabel + "] ¡Disparo en la cabeza! Eliminación instantánea.";
            } else if (relativeHeight >= 0.35) {
                message = "§c[" + sourceLabel + "] ¡Impacto en el torso! (-6 corazones)";
            } else {
                message = "§e[" + sourceLabel + "] Impacto en las extremidades inferiores. (-2.5 corazones)";
            }

            runOnServerOrNow(attacker, () -> {
                LivingEntity authoritativeTarget = targetEntity;
                if (attacker.level() instanceof ServerLevel serverLevel) {
                    Entity serverEntity = serverLevel.getEntity(targetEntity.getUUID());
                    if (serverEntity instanceof LivingEntity le && le.isAlive() && !le.isRemoved()) {
                        authoritativeTarget = le;
                    }
                }
                if (!authoritativeTarget.isAlive() || authoritativeTarget.isRemoved()) return;

                if (relativeHeight >= 0.78) {
                    authoritativeTarget.hurt(authoritativeTarget.level().damageSources().playerAttack(attacker), authoritativeTarget.getMaxHealth() * 100.0f + 999.0f);
                    if (authoritativeTarget.isAlive() && !authoritativeTarget.isRemoved()) {
                        authoritativeTarget.kill();
                    }
                } else if (relativeHeight >= 0.35) {
                    authoritativeTarget.hurt(authoritativeTarget.level().damageSources().playerAttack(attacker), 12.0f);
                } else {
                    authoritativeTarget.hurt(authoritativeTarget.level().damageSources().playerAttack(attacker), 5.0f);
                }
            });

            runOnClientOrNow(() -> feedbackPlayer.displayClientMessage(Component.literal(message), true));
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
            if (!clientPlayer.isAlive()) return;
            if (STATE.droneCooldownTicksLeft > 0) {
                float secondsLeft = STATE.droneCooldownTicksLeft / 20.0f;
                clientPlayer.displayClientMessage(Component.literal(
                        "§e[Rick Prime] Recargando dron: " + String.format("%.1f", secondsLeft) + "s"), true);
                return;
            }

            if (STATE.activeDrones.size() >= MAX_DRONES) {
                clientPlayer.displayClientMessage(Component.literal(
                        "§c[Rick Prime] Límite alcanzado (" + MAX_DRONES + "/" + MAX_DRONES + " drones activos)."), true);
                return;
            }

            int toSpawn = Math.min(DRONES_PER_SUMMON, MAX_DRONES - STATE.activeDrones.size());
            for (int i = 0; i < toSpawn; i++) {
                int slot = findFreeDroneSlot();
                Vec3 staticOffset = getStaticDroneOffset(slot);
                Vec3 spawnPos = clientPlayer.position().add(staticOffset);
                STATE.activeDrones.add(new PrimeDrone(spawnPos, clientPlayer.getYRot(), slot));
                spawnTeleportParticles(clientPlayer, spawnPos.x, spawnPos.y, spawnPos.z);
            }

            STATE.droneCooldownTicksLeft = DRONE_COOLDOWN_TICKS;
            clientPlayer.displayClientMessage(Component.literal(
                    "§a[Rick Prime] ¡Dron generado! (" + STATE.activeDrones.size() + "/" + MAX_DRONES + " activos)"), true);
        }

        private static Vec3 getStaticDroneOffset(int slotIndex) {
            int col = (slotIndex % 5) - 2;
            int row = slotIndex / 5;
            double xOffset = col * 0.65;
            double yOffset = 1.65 + row * 0.45;
            double zOffset = -0.75;
            return new Vec3(xOffset, yOffset, zOffset);
        }

        private static Vec3 pickDroneAimPoint(LivingEntity target, Random random, int tick) {
            double baseY = target.getY();
            double h = Math.max(0.4, target.getBbHeight());
            int zoneRoll = Math.floorMod(tick + random.nextInt(3), 5);
            double heightFactor;
            if (zoneRoll == 0) {
                heightFactor = 0.88;
            } else if (zoneRoll <= 2) {
                heightFactor = 0.52;
            } else {
                heightFactor = 0.18;
            }
            return new Vec3(
                    target.getX(),
                    baseY + h * heightFactor,
                    target.getZ()
            );
        }

        private static void updateDrones(Player clientPlayer) {
            if (STATE.activeDrones.isEmpty()) return;

            Player realPlayer = resolveAuthoritative(clientPlayer);
            AABB twoByTwoChunksBox = new AABB(
                    realPlayer.getX() - DRONE_SEARCH_HALF_EXTENT_XZ,
                    realPlayer.getY() - DRONE_SEARCH_HALF_EXTENT_Y,
                    realPlayer.getZ() - DRONE_SEARCH_HALF_EXTENT_XZ,
                    realPlayer.getX() + DRONE_SEARCH_HALF_EXTENT_XZ,
                    realPlayer.getY() + DRONE_SEARCH_HALF_EXTENT_Y,
                    realPlayer.getZ() + DRONE_SEARCH_HALF_EXTENT_XZ
            );

            List<LivingEntity> nearbyHostiles = realPlayer.level().getEntitiesOfClass(
                    LivingEntity.class,
                    twoByTwoChunksBox,
                    e -> isHostileTarget(e, realPlayer)
            );
            nearbyHostiles.sort(Comparator.comparingDouble(e -> e.distanceToSqr(realPlayer)));

            Set<UUID> targetedThisTick = new HashSet<>();
            List<PrimeDrone> expired = new ArrayList<>();
            for (int i = 0; i < STATE.activeDrones.size(); i++) {
                PrimeDrone drone = STATE.activeDrones.get(i);
                drone.ageTicks++;
                if (drone.attackCooldown > 0) {
                    drone.attackCooldown--;
                }

                Vec3 idlePos = clientPlayer.position().add(getStaticDroneOffset(drone.slotIndex));
                drone.pos = drone.pos.lerp(idlePos, 0.25);

                LivingEntity assignedTarget = null;
                if (!nearbyHostiles.isEmpty()) {
                    LivingEntity candidate = null;
                    for (int offset = 0; offset < nearbyHostiles.size(); offset++) {
                        LivingEntity option = nearbyHostiles.get((i + offset) % nearbyHostiles.size());
                        if (!targetedThisTick.contains(option.getUUID())) {
                            candidate = option;
                            break;
                        }
                    }
                    if (candidate == null) {
                        candidate = nearbyHostiles.get(i % nearbyHostiles.size());
                    }
                    Vec3 candidateCenter = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
                    if (hasLineOfSight(realPlayer.level(), drone.pos, candidateCenter, realPlayer)) {
                        assignedTarget = candidate;
                    }
                }

                if (assignedTarget != null && assignedTarget.isAlive() && !assignedTarget.isRemoved()) {
                    drone.ticksWithoutEnemy = 0;

                    if (drone.lockedAimPoint == null) {
                        drone.lockedAimPoint = pickDroneAimPoint(assignedTarget, realPlayer.getRandom(), drone.ageTicks + i);
                    } else {
                        double relY = Math.max(0.1, Math.min(assignedTarget.getBbHeight(), drone.lockedAimPoint.y - assignedTarget.getY()));
                        drone.lockedAimPoint = new Vec3(assignedTarget.getX(), assignedTarget.getY() + relY, assignedTarget.getZ());
                    }

                    double dx = drone.lockedAimPoint.x - drone.pos.x;
                    double dz = drone.lockedAimPoint.z - drone.pos.z;
                    if (dx * dx + dz * dz > 0.0001) {
                        drone.yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                    }

                    if (drone.attackCooldown <= 0) {
                        drone.attackCooldown = 20;
                        targetedThisTick.add(assignedTarget.getUUID());
                        Vec3 shotPoint = drone.lockedAimPoint;
                        STATE.activeBeams.add(new RedBeamEffect(drone.pos, shotPoint));
                        spawnRedBeamParticles(realPlayer, drone.pos, shotPoint);
                        applyZoneDamageToMob(clientPlayer, realPlayer, assignedTarget, shotPoint, "Dron");
                        drone.lockedAimPoint = null;
                    }
                } else {
                    drone.lockedAimPoint = null;
                    drone.ticksWithoutEnemy++;
                    drone.yaw = clientPlayer.getYRot();

                    if (drone.ticksWithoutEnemy >= DRONE_IDLE_TIMEOUT_TICKS) {
                        spawnTeleportParticles(clientPlayer, drone.pos.x, drone.pos.y, drone.pos.z);
                        expired.add(drone);
                    }
                }
            }

            if (!expired.isEmpty()) {
                STATE.activeDrones.removeAll(expired);
                clientPlayer.displayClientMessage(Component.literal(
                        "§7[Rick Prime] Un dron desapareció tras 2 minutos sin detectar enemigos."), true);
            }
        }

        private static boolean isArrowSource(DamageSource source) {
            return source.getDirectEntity() instanceof AbstractArrow || source.is(DamageTypeTags.IS_PROJECTILE);
        }

        private static void refreshOrangeShield(Player player) {
            boolean wasOff = STATE.orangeShieldCooldownTicks <= 0;
            STATE.orangeShieldCooldownTicks = BodyState.SHIELD_IDLE_TIMEOUT_TICKS;
            if (wasOff) {
                runOnClientOrNow(() -> player.displayClientMessage(Component.literal("§6[Rick Prime] ¡Esfera naranja activada!"), true));
            }
        }

        private static void clampPlayerToOneHeart(Player player) {
            Player auth = resolveAuthoritative(player);
            runOnServerOrNow(auth, () -> {
                if (auth.getHealth() < 2.0f) {
                    auth.setHealth(2.0f);
                }
                auth.setAbsorptionAmount(0.0f);
                auth.clearFire();
                applyMovementPenalty(auth);
            });
            runOnClientOrNow(() -> {
                if (player.getHealth() < 2.0f) {
                    player.setHealth(2.0f);
                }
                player.setAbsorptionAmount(0.0f);
                player.clearFire();
                STATE.isDown = true;
                applyMovementPenalty(player);
            });
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

            if (event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
                return;
            }

            event.setCanceled(true);
            clampPlayerToOneHeart(player);
            runOnClientOrNow(() -> {
                refreshFallenLimbSkins(player);
                player.displayClientMessage(Component.literal(
                        "§4[Rick Prime] ¡Muerte evitada! Quedaste a 1 corazón. Presiona 'R' para regenerarte."), true);
            });
        }

        @SubscribeEvent
        public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            LivingEntity newTarget = event.getTarget();
            if (newTarget == null || newTarget.getId() != player.getId()) return;

            if (shouldMobStopAttacking(event.getEntity(), player)) {
                event.setCanceled(true);
                if (event.getEntity() instanceof Mob mob) {
                    mob.setTarget(null);
                    mob.setLastHurtByMob(null);
                }
            }
        }

        @SubscribeEvent
        public static void onLivingAttack(LivingAttackEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();
            runOnClientOrNow(() -> refreshFallenLimbSkins(player));

            if (STATE.evasionActive) {
                event.setCanceled(true);
                runOnClientOrNow(() -> performEvasionTeleport(player));
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
                mob.setLastHurtByMob(null);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onPlayerHurt(LivingHurtEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player player = (Player) event.getEntity();
            Player authPlayer = resolveAuthoritative(player);
            runOnClientOrNow(() -> refreshFallenLimbSkins(player));

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

            float currentHealth = authPlayer != player ? authPlayer.getHealth() : player.getHealth();
            float currentAbsorption = authPlayer.getAbsorptionAmount();
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
                    runOnServerOrNow(authPlayer, () -> {
                        authPlayer.setAbsorptionAmount(0.0f);
                        authPlayer.setHealth(2.0f);
                        authPlayer.clearFire();
                        applyMovementPenalty(authPlayer);
                    });
                    runOnClientOrNow(() -> {
                        player.setAbsorptionAmount(0.0f);
                        player.setHealth(2.0f);
                        player.clearFire();
                        STATE.isDown = true;
                        if (limbsToLaunch >= 4) {
                            STATE.isImmobilized = true;
                        }
                    });
                }

                runOnClientOrNow(() -> {
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
                });
                return;
            }

            if (isFall && (effectiveDamage >= 18.0f || currentHealth - effectiveDamage <= 2.0f)) {
                event.setCanceled(true);
                runOnServerOrNow(authPlayer, () -> {
                    authPlayer.setAbsorptionAmount(0.0f);
                    authPlayer.setHealth(2.0f);
                    applyMovementPenalty(authPlayer);
                });
                runOnClientOrNow(() -> {
                    player.setAbsorptionAmount(0.0f);
                    player.setHealth(2.0f);
                    processLostHealthLimbs(player, actualTotalLost, false);
                    STATE.isDown = true;
                    applyMovementPenalty(player);
                    player.displayClientMessage(Component.literal(
                            "§4[Rick Prime] ¡Caída severa! Quedaste a 1 corazón (inmortal). Presiona 'R' para regenerarte."), true);
                });
                return;
            }

            if (currentHealth - effectiveDamage <= 2.0f) {
                event.setCanceled(true);
                runOnServerOrNow(authPlayer, () -> {
                    authPlayer.setAbsorptionAmount(0.0f);
                    authPlayer.setHealth(2.0f);
                    applyMovementPenalty(authPlayer);
                });
                runOnClientOrNow(() -> {
                    player.setAbsorptionAmount(0.0f);
                    player.setHealth(2.0f);
                    processLostHealthLimbs(player, actualTotalLost, false);
                    STATE.isDown = true;
                    applyMovementPenalty(player);
                    player.displayClientMessage(Component.literal(
                            "§4[Rick Prime] ¡Llegaste a 1 corazón! Eres inmortal. Presiona 'R' para regenerarte."), true);
                });
                return;
            }

            runOnClientOrNow(() -> processLostHealthLimbs(player, effectiveDamage, false));
        }

        @SubscribeEvent
        public static void onUseItemFinish(LivingEntityUseItemEvent.Finish event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = (Player) event.getEntity();
            Player authPlayer = resolveAuthoritative(localPlayer);

            ItemStack stack = event.getItem();
            if (!stack.isEdible() || stack.getFoodProperties(localPlayer) == null) return;

            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE) || stack.is(Items.GOLDEN_APPLE)) {
                runOnServerOrNow(authPlayer, () -> {
                    FoodData foodData = authPlayer.getFoodData();
                    foodData.eat(2, 0.3f);
                });
            }
            runOnClientOrNow(() -> localPlayer.displayClientMessage(Component.literal(
                    "§e[Rick Prime] Comida consumida (sin regenerar vida ni extremidades)."), true));
        }

        private static void launchLimbsCount(Player player, int count, boolean flyingOut) {
            int launched = 0;
            while (launched < count && STATE.partsLost < LOSS_ORDER.length) {
                loseNextBodyPart(player, flyingOut);
                launched++;
            }
        }

        private static void processLostHealthLimbs(Player player, float lostHealth, boolean heavyFall) {
            if (lostHealth <= 0.0f || STATE.partsLost >= LOSS_ORDER.length) return;
            STATE.healthLostAccumulated += lostHealth;

            while (STATE.healthLostAccumulated >= 4.0f && STATE.partsLost < LOSS_ORDER.length) {
                STATE.healthLostAccumulated -= 4.0f;
                loseNextBodyPart(player, heavyFall);
            }
            if (STATE.partsLost >= LOSS_ORDER.length) {
                STATE.healthLostAccumulated = 0.0f;
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
        public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            tryFireRedBeam(localPlayer);
        }

        @SubscribeEvent
        public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            if (tryFireRedBeam(localPlayer)) {
                event.setCanceled(true);
                return;
            }

            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onAttackEntity(AttackEntityEvent event) {
            if (!isLocalPlayer(event.getEntity())) return;
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            if (tryFireRedBeam(localPlayer)) {
                event.setCanceled(true);
                return;
            }

            if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                event.setCanceled(true);
                localPlayer.displayClientMessage(Component.literal("§c[Rick Prime] ¡No tienes el brazo derecho en tu cuerpo para atacar!"), true);
            }
        }

        @SubscribeEvent
        public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
            Player localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null) return;

            if (event.isAttack()) {
                if (tryFireRedBeam(localPlayer)) {
                    event.setCanceled(true);
                    event.setSwingHand(false);
                    return;
                }
                if (STATE.armDetached(BodyPart.RIGHT_ARM)) {
                    event.setCanceled(true);
                    event.setSwingHand(false);
                }
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
            Player auth = resolveAuthoritative(player);
            runOnServerOrNow(auth, () -> applyMovementPenalty(auth));
        }

        private static void applyMovementPenalty(Player player) {
            if (player == null) return;
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
            if (player == null) return;
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(SPEED_MODIFIER_ID);
        }

        private static double findSafeTeleportY(Level level, double x, double baseY, double z) {
            int ix = (int) Math.floor(x);
            int iz = (int) Math.floor(z);
            int startY = Math.max(level.getMinBuildHeight() + 2, Math.min(level.getMaxBuildHeight() - 4, (int) Math.floor(baseY)));
            for (int dy = 0; dy <= 4; dy++) {
                BlockPos feet = new BlockPos(ix, startY + dy, iz);
                BlockPos head = feet.above();
                BlockState feetState = level.getBlockState(feet);
                BlockState headState = level.getBlockState(head);
                boolean isLava = feetState.getFluidState().is(Fluids.LAVA) || feetState.getFluidState().is(Fluids.FLOWING_LAVA)
                        || headState.getFluidState().is(Fluids.LAVA) || headState.getFluidState().is(Fluids.FLOWING_LAVA);
                if (!isLava && feetState.getCollisionShape(level, feet).isEmpty() && headState.getCollisionShape(level, head).isEmpty()) {
                    return startY + dy;
                }
            }
            for (int dy = -1; dy >= -2; dy--) {
                int cy = startY + dy;
                if (cy <= level.getMinBuildHeight() + 1) break;
                BlockPos feet = new BlockPos(ix, cy, iz);
                BlockPos head = feet.above();
                BlockState feetState = level.getBlockState(feet);
                BlockState headState = level.getBlockState(head);
                boolean isLava = feetState.getFluidState().is(Fluids.LAVA) || feetState.getFluidState().is(Fluids.FLOWING_LAVA)
                        || headState.getFluidState().is(Fluids.LAVA) || headState.getFluidState().is(Fluids.FLOWING_LAVA);
                if (!isLava && feetState.getCollisionShape(level, feet).isEmpty() && headState.getCollisionShape(level, head).isEmpty()) {
                    return cy;
                }
            }
            return startY;
        }

        private static void performEvasionTeleport(Player clientPlayer) {
            Player real = resolveAuthoritative(clientPlayer);
            Vec3 oldPos = real.position();
            spawnAfterimage(oldPos, real.getYRot());

            double angle = real.getRandom().nextDouble() * Math.PI * 2.0;
            double dist = 4.0 + real.getRandom().nextDouble() * 2.0;
            double nx = oldPos.x + Math.cos(angle) * dist;
            double nz = oldPos.z + Math.sin(angle) * dist;
            double ny = findSafeTeleportY(real.level(), nx, oldPos.y, nz);

            if (ny <= real.level().getMinBuildHeight() + 1
                    || !real.level().getWorldBorder().isWithinBounds(nx, nz)
                    || !real.level().hasChunkAt(BlockPos.containing(nx, ny, nz))) {
                return;
            }

            spawnTeleportParticles(real, oldPos.x, oldPos.y + 1.0, oldPos.z);
            runOnServerOrNow(real, () -> real.teleportTo(nx, ny, nz));
            if (real.getId() != clientPlayer.getId()) {
                clientPlayer.setPos(nx, ny, nz);
            }
            spawnTeleportParticles(real, nx, ny + 1.0, nz);

            clientPlayer.displayClientMessage(Component.literal("§b[Rick Prime] ¡Evasión!"), true);
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
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            if (Minecraft.getInstance().screen != null) return;
            Player player = Minecraft.getInstance().player;
            if (player == null || !player.isAlive()) return;

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
                    spawnAfterimage(player.position(), player.getYRot());
                    player.displayClientMessage(Component.literal(
                            "§b[Rick Prime] ¡Evasión activada por 30s!"), true);
                }
            }

            if (ClientModEvents.SUMMON_DRONES_KEY.consumeClick()) {
                summonDrones(player);
            }
        }

        private static void startRegeneration(Player player) {
            Player authPlayer = resolveAuthoritative(player);
            float maxHp = authPlayer.getMaxHealth();
            STATE.originalMaxHealth = maxHp > 0.0f ? maxHp : 20.0f;
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

            BlockPos sidePos = BlockPos.containing(nextX, limb.worldPos.y + 0.1, nextZ);
            BlockState sideState = player.level().getBlockState(sidePos);
            double velX = limb.velocity.x;
            double velZ = limb.velocity.z;
            if (!sideState.getCollisionShape(player.level(), sidePos).isEmpty()) {
                velX *= -0.35;
                velZ *= -0.35;
                nextX = limb.worldPos.x + velX;
                nextZ = limb.worldPos.z + velZ;
            }

            limb.velocity = new Vec3(velX, limb.velocity.y, velZ);

            BlockPos belowPos = BlockPos.containing(nextX, nextY - 0.12, nextZ);
            BlockState belowState = player.level().getBlockState(belowPos);
            VoxelShape belowShape = belowState.getCollisionShape(player.level(), belowPos);
            boolean hasFloor = !belowShape.isEmpty();
            double shapeTop = hasFloor ? belowShape.max(Direction.Axis.Y) : 1.0;
            double floorTopY = belowPos.getY() + shapeTop;
            boolean landingOnFloor = hasFloor && limb.velocity.y <= 0.0 && nextY <= floorTopY + 0.05;

            double velY = limb.velocity.y;
            if (landingOnFloor) {
                if (velY < -0.18) {
                    velX *= 0.65;
                    velY = -velY * 0.35;
                    velZ *= 0.65;
                    limb.spinYaw *= 0.6f;
                    limb.spinPitch *= 0.6f;
                } else {
                    velX *= 0.78;
                    velY = 0.0;
                    velZ *= 0.78;
                    limb.spinYaw *= 0.5f;
                    limb.spinPitch *= 0.5f;
                    if (velX * velX + velZ * velZ < 0.0004) {
                        velX = 0.0;
                        velZ = 0.0;
                        limb.spinYaw = 0f;
                        limb.spinPitch = 0f;
                    }
                    limb.pitch += (90f - limb.pitch) * 0.35f;
                }
            }

            limb.velocity = new Vec3(velX, velY, velZ);

            double finalY = landingOnFloor && limb.velocity.y == 0.0
                    ? floorTopY
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
            if (player == null || player.isRemoved()) return;

            clientTickCounter++;
            refreshFallenLimbSkins(player);
            updateDrones(player);
            updateEvasionAfterimages(player);

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

            if (!player.isAlive()) return;

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
            float fallbackMaxHp = Math.max(20.0f, Math.max(STATE.originalMaxHealth, realPlayer.getMaxHealth()));
            runOnServerOrNow(realPlayer, () -> {
                float targetMaxHp = Math.max(20.0f, Math.max(STATE.originalMaxHealth, realPlayer.getMaxHealth()));
                float healPerTick = (targetMaxHp - 2.0f) / FallenLimb.RETURN_DURATION;
                if (realPlayer.getHealth() < targetMaxHp) {
                    realPlayer.setHealth(Math.min(targetMaxHp, realPlayer.getHealth() + healPerTick));
                }
            });

            if (allHome) {
                finishRegeneration(player, fallbackMaxHp);
            }
        }

        private static void finishRegeneration(Player player, float targetMaxHp) {
            STATE.regenerating = false;
            STATE.isDown = false;
            STATE.isImmobilized = false;
            STATE.partsLost = 0;
            STATE.healthLostAccumulated = 0.0f;
            STATE.fallenLimbs.clear();
            Player authPlayer = resolveAuthoritative(player);
            runOnServerOrNow(authPlayer, () -> {
                float finalMax = Math.max(targetMaxHp, authPlayer.getMaxHealth());
                authPlayer.setHealth(finalMax);
                if (authPlayer.getId() != player.getId()) {
                    authPlayer.setPose(Pose.STANDING);
                }
                clearMovementPenalty(authPlayer);
            });
            player.setHealth(targetMaxHp);
            player.setPose(Pose.STANDING);
            clearMovementPenalty(player);
            player.displayClientMessage(Component.literal("§a[Rick Prime] ¡Te has regenerado al 100%!"), true);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class PlayerModelHider {

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

            if (model == null) {
                model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            }

            if (!STATE.fallenLimbs.isEmpty()) {
                for (FallenLimb limb : STATE.fallenLimbs) {
                    ResourceLocation tex = limb.skin != null ? limb.skin : DEFAULT_STEVE_SKIN;
                    renderLimb(limb, tex, poseStack, bufferSource, camPos);
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
                }
            }

            if (player != null && !STATE.afterimages.isEmpty()) {
                ResourceLocation primeTex = ForgeClientEvents.resolveRickPrimeSkin(player);
                for (EvasionAfterimage img : STATE.afterimages) {
                    renderAfterimage(img, primeTex, poseStack, bufferSource, camPos);
                }
                bufferSource.endBatch(RenderType.entityTranslucent(primeTex));
            }

            boolean drewTranslucentDefault = false;
            if (player != null && !STATE.activeDrones.isEmpty()) {
                for (PrimeDrone drone : STATE.activeDrones) {
                    renderDrone(drone, poseStack, bufferSource, camPos);
                }
                drewTranslucentDefault = true;
            }

            if (!STATE.activeBeams.isEmpty()) {
                for (RedBeamEffect beam : STATE.activeBeams) {
                    renderRedBeam(beam, poseStack, bufferSource, camPos);
                }
                drewTranslucentDefault = true;
            }

            if (player != null && STATE.orangeShieldCooldownTicks > 0) {
                renderOrangeCircularShield(player, poseStack, bufferSource, camPos, event.getPartialTick());
                drewTranslucentDefault = true;
            }

            if (drewTranslucentDefault) {
                bufferSource.endBatch(RenderType.entityTranslucent(DEFAULT_STEVE_SKIN));
            }
        }

        private static void renderAfterimage(EvasionAfterimage img, ResourceLocation tex,
                                             PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                             Vec3 camPos) {
            float lifeRatio = Math.max(0.0f, 1.0f - (img.age / (float) AFTERIMAGE_MAX_AGE));
            float alpha = 0.65f * lifeRatio;

            model.head.xRot = 0; model.head.yRot = 0; model.head.zRot = 0;
            model.body.xRot = 0; model.body.yRot = 0; model.body.zRot = 0;
            model.rightArm.xRot = -0.7f; model.rightArm.yRot = 0; model.rightArm.zRot = 0;
            model.leftArm.xRot = 0.7f;   model.leftArm.yRot = 0; model.leftArm.zRot = 0;
            model.rightLeg.xRot = 0.6f;  model.rightLeg.yRot = 0; model.rightLeg.zRot = 0;
            model.leftLeg.xRot = -0.6f;  model.leftLeg.yRot = 0; model.leftLeg.zRot = 0;

            model.head.visible = true;
            model.hat.visible = true;
            model.body.visible = true;
            model.jacket.visible = true;
            model.rightArm.visible = true;
            model.leftArm.visible = true;
            model.rightLeg.visible = true;
            model.leftLeg.visible = true;
            model.rightSleeve.visible = true;
            model.leftSleeve.visible = true;
            model.rightPants.visible = true;
            model.leftPants.visible = true;

            poseStack.pushPose();
            poseStack.translate(img.pos.x - camPos.x, img.pos.y - camPos.y, img.pos.z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(img.yaw));
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0, -1.5, 0);

            var buffer = bufferSource.getBuffer(RenderType.entityTranslucent(tex));
            int argb = ((int) (alpha * 255.0f) << 24) | 0x45D5FF;
            float r = ((argb >> 16) & 0xFF) / 255.0f;
            float g = ((argb >> 8) & 0xFF) / 255.0f;
            float b = (argb & 0xFF) / 255.0f;
            model.renderToBuffer(poseStack, buffer, 15728880, OverlayTexture.NO_OVERLAY, r, g, b, alpha);

            poseStack.popPose();
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
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(DEFAULT_STEVE_SKIN));
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
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(DEFAULT_STEVE_SKIN));

            float pulse = 0.88f + 0.12f * (float) Math.sin(drone.ageTicks * 0.35f);

            addBox(consumer, mat, -0.17f, -0.14f, -0.10f,  0.17f,  0.15f, 0.11f, 0.50f, 0.56f, 0.64f, 0.98f);
            addBox(consumer, mat, -0.14f, -0.12f,  0.09f,  0.14f,  0.14f, 0.13f, 0.74f, 0.83f, 0.92f, 0.98f);

            addBox(consumer, mat, -0.23f,  0.13f, -0.09f, -0.09f,  0.35f, 0.10f, 0.62f, 0.70f, 0.79f, 0.98f);
            addBox(consumer, mat, -0.21f,  0.15f,  0.05f, -0.11f,  0.34f, 0.12f, 0.80f, 0.88f, 0.96f, 0.98f);

            addBox(consumer, mat,  0.09f,  0.13f, -0.09f,  0.23f,  0.35f, 0.10f, 0.62f, 0.70f, 0.79f, 0.98f);
            addBox(consumer, mat,  0.11f,  0.15f,  0.05f,  0.21f,  0.34f, 0.12f, 0.80f, 0.88f, 0.96f, 0.98f);

            addBox(consumer, mat, -0.09f,  0.12f, -0.08f,  0.09f,  0.19f, 0.09f, 0.55f, 0.63f, 0.72f, 0.96f);

            addBox(consumer, mat, -0.27f, -0.02f, -0.08f, -0.16f,  0.10f, 0.09f, 0.72f, 0.82f, 0.91f, 0.97f);
            addBox(consumer, mat,  0.16f, -0.02f, -0.08f,  0.27f,  0.10f, 0.09f, 0.72f, 0.82f, 0.91f, 0.97f);

            addBox(consumer, mat, -0.35f, -0.11f, -0.07f, -0.17f, -0.01f, 0.07f, 0.48f, 0.74f, 0.89f, 0.96f);
            addBox(consumer, mat,  0.17f, -0.11f, -0.07f,  0.35f, -0.01f, 0.07f, 0.48f, 0.74f, 0.89f, 0.96f);

            addBox(consumer, mat, -0.25f, -0.25f, -0.08f, -0.11f, -0.14f, 0.08f, 0.44f, 0.71f, 0.86f, 0.96f);
            addBox(consumer, mat,  0.11f, -0.25f, -0.08f,  0.25f, -0.14f, 0.08f, 0.44f, 0.71f, 0.86f, 0.96f);
            addBox(consumer, mat, -0.11f, -0.27f, -0.07f,  0.11f, -0.15f, 0.07f, 0.36f, 0.56f, 0.72f, 0.96f);
            addBox(consumer, mat, -0.07f, -0.26f,  0.05f, -0.01f, -0.18f, 0.09f, 0.62f, 0.86f, 0.98f, 0.98f);
            addBox(consumer, mat,  0.01f, -0.26f,  0.05f,  0.07f, -0.18f, 0.09f, 0.62f, 0.86f, 0.98f, 0.98f);

            addBox(consumer, mat, -0.035f, 0.085f, 0.125f, 0.035f, 0.145f, 0.155f, 0.35f, 0.88f, 1.00f, 1.00f);

            addBox(consumer, mat, -0.125f, -0.115f, 0.115f,  0.125f,  0.085f, 0.150f, 0.85f * pulse, 0.14f, 0.05f, 0.98f);
            addBox(consumer, mat, -0.100f, -0.095f, 0.135f,  0.100f,  0.065f, 0.168f, 1.00f * pulse, 0.42f * pulse, 0.08f, 1.00f);
            addBox(consumer, mat, -0.055f, -0.055f, 0.155f,  0.055f,  0.030f, 0.180f, 1.00f, 0.76f * pulse, 0.25f, 1.00f);

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
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(DEFAULT_STEVE_SKIN));

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

                    consumer.vertex(mat, x1a, y1, z1a).color(1.0f, 0.53f, 0.0f, alpha).uv(0, 0).overlayCoords(0).uv2(15728880).normal(x1a / radius, y1 / radius, z1a / radius).endVertex();
                    consumer.vertex(mat, x1b, y1, z1b).color(1.0f, 0.53f, 0.0f, alpha).uv(1, 0).overlayCoords(0).uv2(15728880).normal(x1b / radius, y1 / radius, z1b / radius).endVertex();
                    consumer.vertex(mat, x2b, y2, z2b).color(1.0f, 0.53f, 0.0f, alpha).uv(1, 1).overlayCoords(0).uv2(15728880).normal(x2b / radius, y2 / radius, z2b / radius).endVertex();
                    consumer.vertex(mat, x2a, y2, z2a).color(1.0f, 0.53f, 0.0f, alpha).uv(0, 1).overlayCoords(0).uv2(15728880).normal(x2a / radius, y2 / radius, z2a / radius).endVertex();
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
