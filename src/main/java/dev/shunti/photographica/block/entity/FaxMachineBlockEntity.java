package dev.hitom.photographica.block.entity;

import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.registry.ModBlockEntities;
import dev.hitom.photographica.screen.FaxMachineScreenHandler;
import dev.hitom.photographica.screen.FaxOpenData;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
//? if >=1.21.11 {
/*import net.minecraft.storage.WriteView;
import net.minecraft.storage.ReadView;*/
//?} else {
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
//?}
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small flying-camera-adjacent piece of infrastructure: sends a {@link PhotoItem} to
 * whichever other fax machine has the matching {@code machineNumber} — a real fax's "phone
 * number", not a player name, since two players can each own several machines and the sender
 * doesn't need to know who's on the other end, only which physical machine to ring.
 */
public class FaxMachineBlockEntity extends BlockEntity implements Inventory, ExtendedScreenHandlerFactory<FaxOpenData> {
    public static final int SLOT_OUT = 0;
    public static final int SLOT_IN = 1;

    /**
     * Server-side directory from a machine's number to wherever it currently is, so a send
     * doesn't need to scan every loaded chunk in every world. Populated opportunistically
     * (whenever a machine's data is loaded from disk, or its screen is opened) rather than kept
     * perfectly in sync — nothing else in this mod tracks cross-block-entity state
     * persistently either, and a stale entry is harmless: {@link #find} re-validates that
     * whatever's actually at the recorded position is still a live machine with a matching
     * number before anything is delivered to it.
     */
    private static final Map<Integer, Location> REGISTRY = new ConcurrentHashMap<>();

    private record Location(net.minecraft.registry.RegistryKey<World> dimension, BlockPos pos) {}

    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(2, ItemStack.EMPTY);
    private int machineNumber = -1;

    public FaxMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FAX_MACHINE, pos, state);
    }

    public int getMachineNumber() {
        ensureNumber();
        return machineNumber;
    }

    /** {@code World.isClient} became the private-backed {@code isClient()} at 1.21.11. */
    private boolean isClientSide() {
        //? if >=1.21.11 {
        /*return world != null && world.isClient();
        *///?} else {
        return world != null && world.isClient;
        //?}
    }

    private void ensureNumber() {
        if (machineNumber < 0 && world != null && !isClientSide()) {
            machineNumber = 1000 + world.random.nextInt(9000);
            markDirty();
        }
    }

    private void ensureRegistered() {
        ensureNumber();
        if (world != null && !isClientSide() && machineNumber >= 0) {
            REGISTRY.put(machineNumber, new Location(world.getRegistryKey(), pos.toImmutable()));
        }
    }

    /** Looks up a fax machine by number for delivery — always re-validated against the live
     *  world rather than trusted outright, since {@link #REGISTRY} is only ever a hint. */
    @Nullable
    public static FaxMachineBlockEntity find(MinecraftServer server, int number) {
        Location loc = REGISTRY.get(number);
        if (loc == null) return null;
        World world = server.getWorld(loc.dimension());
        if (world == null) return null;
        // getBlockEntity() itself safely returns null for an unloaded chunk — no separate
        // isChunkLoaded() check needed.
        if (world.getBlockEntity(loc.pos()) instanceof FaxMachineBlockEntity fax
                && fax.getMachineNumber() == number) {
            return fax;
        }
        return null;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("FAX機 #" + getMachineNumber());
    }

    @Override
    public FaxOpenData getScreenOpeningData(ServerPlayerEntity player) {
        return new FaxOpenData(pos.toImmutable(), getMachineNumber());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        ensureRegistered();
        return new FaxMachineScreenHandler(syncId, playerInventory, this, pos.toImmutable(), getMachineNumber());
    }

    // ── Inventory ────────────────────────────────────────────────────────────────
    @Override
    public int size() { return inventory.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) { return inventory.get(slot); }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack result = Inventories.splitStack(inventory, slot, amount);
        if (!result.isEmpty()) markDirty();
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) { return Inventories.removeStack(inventory, slot); }

    @Override
    public void setStack(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (stack.getCount() > getMaxCountPerStack()) {
            stack.setCount(getMaxCountPerStack());
        }
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return Inventory.canPlayerUse(this, player);
    }

    @Override
    public void clear() { inventory.clear(); }

    //? if >=1.21.11 {
    /*@Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        Inventories.writeData(view, inventory);
        view.putInt("MachineNumber", machineNumber);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Inventories.readData(view, inventory);
        machineNumber = view.getInt("MachineNumber", -1);
        ensureRegistered();
    }
    *///?} else {
    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        Inventories.writeNbt(nbt, inventory, registryLookup);
        nbt.putInt("MachineNumber", machineNumber);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        Inventories.readNbt(nbt, inventory, registryLookup);
        machineNumber = nbt.contains("MachineNumber") ? nbt.getInt("MachineNumber") : -1;
        ensureRegistered();
    }
    //?}
}
