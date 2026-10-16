package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.BooleanSettingGroup;
import java.util.Random;

public final class TriggerBot extends Module {
    public final Setting<Float> attackRange = new Setting<>("Range", 3f, 1f, 7.0f);
    public final Setting<BooleanSettingGroup> smartCrit = new Setting<>("SmartCrit", new BooleanSettingGroup(true));
    public final Setting<Boolean> onlySpace = new Setting<>("OnlyCrit", false).addToGroup(smartCrit);
    public final Setting<Boolean> autoJump = new Setting<>("AutoJump", false).addToGroup(smartCrit);
    public final Setting<Boolean> ignoreWalls = new Setting<>("IgnoreWalls", false);
    public final Setting<Boolean> pauseEating = new Setting<>("PauseWhileEating", false);
    public final Setting<Boolean> guiCheck = new Setting<>("GuiCheck", false);
    public final Setting<Boolean> stopWhenDurabilityLow = new Setting<>("StopWhenDurabilityIsLow", false);
    public final Setting<Boolean> autoRepair = new Setting<>("AutoRepair", false);
    public final Setting<Integer> minDelay = new Setting<>("RandomDelayMin", 2, 0, 20);
    public final Setting<Integer> maxDelay = new Setting<>("RandomDelayMax", 13, 0, 20);

    private int delay;
    private final Random random = new Random(); // For random delay
    private int lastGuiSyncId = -1;
    private int lastGuiSlot = -1;
    private boolean repairRequested;

    public TriggerBot() {
        super("TriggerBot", Category.COMBAT);
    }

    @EventHandler
    public void onAttack(PlayerUpdateEvent e) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null)
            return;

        ItemStack weapon = mc.player.getMainHandStack();
        boolean lowDurabilitySword = weapon.getItem() instanceof SwordItem
                && weapon.getMaxDamage() > 0
                && ((weapon.getMaxDamage() - weapon.getDamage()) * 100.0f / weapon.getMaxDamage()) <= 10.0f;

        if (!lowDurabilitySword) {
            repairRequested = false;
        } else if (autoRepair.getValue() && !repairRequested) {
            mc.player.networkHandler.sendChatCommand("repair");
            repairRequested = true;
        }

        // Only treat handled/container screens as game GUIs. Options, ClickGUI, and
        // losing window focus (Alt+Tab) do not count as an open container GUI.
        if (guiCheck.getValue() && mc.currentScreen instanceof HandledScreen<?>) {
            findAndClickConfirmItem();
            return;
        }
        lastGuiSyncId = -1;
        lastGuiSlot = -1;

        if (stopWhenDurabilityLow.getValue() && lowDurabilitySword) {
            return;
        }

        if (mc.player.isUsingItem() && pauseEating.getValue()) {
            return;
        }
        if (!mc.options.jumpKey.isPressed() && mc.player.isOnGround() && autoJump.getValue())
            mc.player.jump();

        // Smart crits should not be delayed
        if (!autoCrit()) {
            if (delay > 0) {
                delay--;
                return;
            }
        }

        Entity ent = Managers.PLAYER.getRtxTarget(mc.player.getYaw(), mc.player.getPitch(), attackRange.getValue(), ignoreWalls.getValue());
        if (ent != null && !Managers.FRIEND.isFriend(ent.getName().getString())) {
            mc.interactionManager.attackEntity(mc.player, ent);
            mc.player.swingHand(Hand.MAIN_HAND);

            // Set delay for the next hit (10 to 20 ms)
            delay = random.nextInt(minDelay.getValue(), maxDelay.getValue() + 1) ; // (20ms / 50ms per tick = ~0.4 ticks, 10ms / 50ms = ~0.2 ticks)
            // ulybaka1337: am i cooking???
            // default delay is calculated with
            // nextInt(11) + 2
            // so max value is 11+2=13 and min is 2
        }
    }


    private void findAndClickConfirmItem() {
        if (mc.player == null || mc.interactionManager == null)
            return;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;
        // The final 36 slots are the player's inventory; inspect only slots belonging
        // to the opened container/menu so an item in the player's inventory is ignored.
        int containerSlots = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty())
                continue;

            boolean matches = stack.getName().getString().toLowerCase().contains("click to confirm");
            var lore = stack.get(DataComponentTypes.LORE);
            if (!matches && lore != null) {
                for (var line : lore.lines()) {
                    if (line.getString().toLowerCase().contains("click to confirm")) {
                        matches = true;
                        break;
                    }
                }
            }

            if (matches) {
                // Click a matching slot only once per open screen/slot, avoiding repeated clicks.
                if (lastGuiSyncId != syncId || lastGuiSlot != i) {
                    mc.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    lastGuiSyncId = syncId;
                    lastGuiSlot = i;
                }
                return;
            }
        }
        lastGuiSyncId = -1;
        lastGuiSlot = -1;
    }

    private boolean autoCrit() {
        boolean reasonForSkipCrit =
                !smartCrit.getValue().isEnabled()
                        || mc.player.getAbilities().flying
                        || (mc.player.isFallFlying() || ModuleManager.elytraPlus.isEnabled())
                        || mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                        || mc.player.isHoldingOntoLadder()
                        || mc.world.getBlockState(BlockPos.ofFloored(mc.player.getPos())).getBlock() == Blocks.COBWEB;

        if (mc.player.fallDistance > 1 && mc.player.fallDistance < 1.14)
            return false;

        if (ModuleManager.aura.getAttackCooldown() < (mc.player.isOnGround() ? 1f : 0.9f))
            return false;

        boolean mergeWithTargetStrafe = !ModuleManager.targetStrafe.isEnabled() || !ModuleManager.targetStrafe.jump.getValue();
        boolean mergeWithSpeed = !ModuleManager.speed.isEnabled() || mc.player.isOnGround();

        if (!mc.options.jumpKey.isPressed() && mergeWithTargetStrafe && mergeWithSpeed && !onlySpace.getValue() && !autoJump.getValue())
            return true;

        if (mc.player.isInLava())
            return true;

        if (!mc.options.jumpKey.isPressed() && ModuleManager.aura.isAboveWater())
            return true;

        if (!reasonForSkipCrit)
            return !mc.player.isOnGround() && mc.player.fallDistance > 0.0f;
        return true;
    }
}

