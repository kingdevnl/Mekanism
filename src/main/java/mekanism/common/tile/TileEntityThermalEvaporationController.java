package mekanism.common.tile;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import mekanism.api.Coord4D;
import mekanism.api.IEvaporationSolar;
import mekanism.api.TileNetworkList;
import mekanism.api.annotations.NonNull;
import mekanism.api.recipes.FluidToFluidRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.FluidToFluidCachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.api.recipes.outputs.OutputHelper;
import mekanism.common.Mekanism;
import mekanism.common.MekanismBlock;
import mekanism.common.base.IActiveState;
import mekanism.common.base.ITankManager;
import mekanism.common.base.LazyOptionalHelper;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.tank.TankUpdateProtocol;
import mekanism.common.inventory.IInventorySlotHolder;
import mekanism.common.inventory.InventorySlotHelper;
import mekanism.common.inventory.slot.FluidInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.tile.interfaces.ITileCachedRecipeHolder;
import mekanism.common.util.CapabilityUtils;
import mekanism.common.util.FluidContainerUtils;
import mekanism.common.util.FluidContainerUtils.FluidChecker;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.TileUtils;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.CapabilityItemHandler;

public class TileEntityThermalEvaporationController extends TileEntityThermalEvaporationBlock implements IActiveState, ITankManager,
      ITileCachedRecipeHolder<FluidToFluidRecipe> {

    public static final int MAX_OUTPUT = 10000;
    public static final int MAX_SOLARS = 4;
    public static final int MAX_HEIGHT = 18;
    private static final int[] SLOTS = {0, 1, 2, 3};

    public FluidTank inputTank = new FluidTank(0);
    public FluidTank outputTank = new FluidTank(MAX_OUTPUT);

    public Set<Coord4D> tankParts = new HashSet<>();
    public IEvaporationSolar[] solars = new IEvaporationSolar[4];

    public boolean temperatureSet = false;

    public double partialInput = 0;
    public double partialOutput = 0;

    public float biomeTemp = 0;
    //TODO: 1.14 potentially convert temperature to a double given we are using a DoubleSupplier anyways
    // Will make it so we don't have cast issues from the configs. Doing so in 1.12 may be slightly annoying
    // due to the fact the variables are stored in NBT as floats. Even though it should be able to load the float as a double
    public float temperature = 0;
    public float heatToAbsorb = 0;

    public float lastGain = 0;

    public int height = 0;

    public boolean structured = false;
    public boolean controllerConflict = false;
    public boolean isLeftOnFace;
    public int renderY;

    public boolean updatedThisTick = false;

    public int clientSolarAmount;
    public boolean clientStructured;

    public float prevScale;

    public float totalLoss = 0;

    private CachedRecipe<FluidToFluidRecipe> cachedRecipe;

    private final IOutputHandler<@NonNull FluidStack> outputHandler;
    private final IInputHandler<@NonNull FluidStack> inputHandler;

    //TODO: Better names?
    private FluidInventorySlot inputInputSlot;
    private OutputInventorySlot outputInputSlot;
    private FluidInventorySlot inputOutputSlot;
    private OutputInventorySlot outputOutputSlot;

    public TileEntityThermalEvaporationController() {
        super(MekanismBlock.THERMAL_EVAPORATION_CONTROLLER);
        inputHandler = InputHelper.getInputHandler(inputTank, 0);
        outputHandler = OutputHelper.getOutputHandler(outputTank);
    }

    @Nonnull
    @Override
    protected IInventorySlotHolder getInitialInventory() {
        //TODO: Make the inventory be accessible via the valves instead
        InventorySlotHelper.Builder builder = InventorySlotHelper.Builder.forSide(this::getDirection);
        builder.addSlot(inputInputSlot = FluidInventorySlot.fill(inputTank, fluid -> containsRecipe(recipe -> recipe.getInput().testType(fluid)), 28, 20));
        builder.addSlot(outputInputSlot = OutputInventorySlot.at(28, 51));
        builder.addSlot(inputOutputSlot = FluidInventorySlot.drain(outputTank, 132, 20));
        builder.addSlot(outputOutputSlot = OutputInventorySlot.at(132, 51));
        return builder.build();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!isRemote()) {
            updatedThisTick = false;
            if (ticker == 5) {
                refresh();
            }
            if (structured) {
                updateTemperature();
                manageBuckets();
            }
            //Note: This is not in a structured check as we want to make sure it stops if we do not have a structure
            //TODO: Think through the logic, given we are calling the process so technically if it is not structured, then we
            // don't actually have it processing so we don't need this outside of the structured? Verify
            cachedRecipe = getUpdatedCache(0);
            if (cachedRecipe != null) {
                cachedRecipe.process();
            }
            if (structured) {
                if (Math.abs((float) inputTank.getFluidAmount() / inputTank.getCapacity() - prevScale) > 0.01) {
                    Mekanism.packetHandler.sendUpdatePacket(this);
                    prevScale = (float) inputTank.getFluidAmount() / inputTank.getCapacity();
                }
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        refresh();
    }

    @Override
    public void onNeighborChange(Block block) {
        super.onNeighborChange(block);
        refresh();
    }

    public boolean hasRecipe(FluidStack fluid) {
        return containsRecipe(recipe -> recipe.getInput().testType(fluid));
    }

    protected void refresh() {
        if (!isRemote()) {
            if (!updatedThisTick) {
                clearStructure();
                structured = buildStructure();
                if (structured != clientStructured) {
                    Mekanism.packetHandler.sendUpdatePacket(this);
                    clientStructured = structured;
                }

                if (structured) {
                    inputTank.setCapacity(getMaxFluid());

                    if (!inputTank.getFluid().isEmpty()) {
                        inputTank.getFluid().setAmount(Math.min(inputTank.getFluid().getAmount(), getMaxFluid()));
                    }
                } else {
                    clearStructure();
                }
            }
        }
    }

    @Nonnull
    @Override
    public MekanismRecipeType<FluidToFluidRecipe> getRecipeType() {
        return MekanismRecipeType.EVAPORATING;
    }

    @Nullable
    @Override
    public CachedRecipe<FluidToFluidRecipe> getCachedRecipe(int cacheIndex) {
        return cachedRecipe;
    }

    @Nullable
    @Override
    public FluidToFluidRecipe getRecipe(int cacheIndex) {
        FluidStack fluid = inputHandler.getInput();
        if (fluid.isEmpty()) {
            return null;
        }
        return findFirstRecipe(recipe -> recipe.test(fluid));
    }

    @Nullable
    @Override
    public CachedRecipe<FluidToFluidRecipe> createNewCachedRecipe(@Nonnull FluidToFluidRecipe recipe, int cacheIndex) {
        //TODO: Have lastGain be set properly, and our setActive -> false set lastGain to zero
        //TODO: HANDLE ALL THIS STUFF, A good chunk of it can probably go in the getOutputHandler (or in a custom one we pass from here)
        // But none of it should remain outside of what gets passed in one way or another to the cached recipe
        return new FluidToFluidCachedRecipe(recipe, inputHandler, outputHandler)
              .setCanHolderFunction(() -> structured && height > 2 && height <= MAX_HEIGHT && MekanismUtils.canFunction(this))
              .setOnFinish(this::markDirty)
              .setPostProcessOperations(currentMax -> {
                  if (currentMax == 0) {
                      //Short circuit that if we already can't perform any outputs, just return
                      return 0;
                  }

                  double tempMult = Math.max(0, getTemperature()) * MekanismConfig.general.evaporationTempMultiplier.get();
                  double multiplier = tempMult * height / (float) MAX_HEIGHT;
                  //TODO: See how close all these checks are to properly calculating usage
                  //Also set values like lastGain
                  return Math.min(MekanismUtils.clampToInt(currentMax * multiplier), currentMax);
              });
    }

    private void manageBuckets() {
        if (!outputTank.getFluid().isEmpty()) {
            if (FluidContainerUtils.isFluidContainer(getStackInSlot(2))) {
                FluidContainerUtils.handleContainerItemFill(this, outputTank, inputOutputSlot, outputOutputSlot);
            }
        }

        if (structured) {
            if (FluidContainerUtils.isFluidContainer(getStackInSlot(0))) {
                FluidContainerUtils.handleContainerItemEmpty(this, inputTank, inputInputSlot, outputInputSlot, new FluidChecker() {
                    @Override
                    public boolean isValid(@Nonnull Fluid f) {
                        return hasRecipe(new FluidStack(f, 1));
                    }
                });
            }
        }
    }

    private void updateTemperature() {
        if (!temperatureSet) {
            biomeTemp = world.getBiomeBody(getPos()).getTemperature(getPos());
            temperatureSet = true;
        }
        heatToAbsorb += getActiveSolars() * MekanismConfig.general.evaporationSolarMultiplier.get();
        temperature += heatToAbsorb / (float) height;

        float biome = biomeTemp - 0.5F;
        float base = biome > 0 ? biome * 20 : biomeTemp * 40;

        if (Math.abs(temperature - base) < 0.001) {
            temperature = base;
        }
        float incr = (float) Math.sqrt(Math.abs(temperature - base)) * MekanismConfig.general.evaporationHeatDissipation.get();

        if (temperature > base) {
            incr = -incr;
        }

        float prev = temperature;
        temperature = (float) Math.min(MekanismConfig.general.evaporationMaxTemp.get(), temperature + incr / (float) height);

        if (incr < 0) {
            totalLoss = prev - temperature;
        } else {
            totalLoss = 0;
        }
        heatToAbsorb = 0;
        MekanismUtils.saveChunk(this);
    }

    public float getTemperature() {
        return temperature;
    }

    public int getActiveSolars() {
        if (isRemote()) {
            return clientSolarAmount;
        }
        int ret = 0;
        for (IEvaporationSolar solar : solars) {
            if (solar != null && solar.canSeeSun()) {
                ret++;
            }
        }
        return ret;
    }

    public boolean buildStructure() {
        Direction right = getRightSide();
        Direction left = getLeftSide();
        height = 0;
        controllerConflict = false;
        updatedThisTick = true;

        Coord4D startPoint = Coord4D.get(this);
        while (startPoint.offset(Direction.UP).getTileEntity(world) instanceof TileEntityThermalEvaporationBlock) {
            startPoint = startPoint.offset(Direction.UP);
        }

        Coord4D test = startPoint.offset(Direction.DOWN).offset(right, 2);
        isLeftOnFace = test.getTileEntity(world) instanceof TileEntityThermalEvaporationBlock;
        startPoint = startPoint.offset(left, isLeftOnFace ? 1 : 2);
        if (!scanTopLayer(startPoint)) {
            return false;
        }

        height = 1;

        Coord4D middlePointer = startPoint.offset(Direction.DOWN);
        while (scanLowerLayer(middlePointer)) {
            middlePointer = middlePointer.offset(Direction.DOWN);
        }
        renderY = middlePointer.y + 1;
        if (height < 3 || height > MAX_HEIGHT) {
            height = 0;
            return false;
        }
        structured = true;
        markDirty();
        return true;
    }

    public boolean scanTopLayer(Coord4D current) {
        Direction right = getRightSide();
        Direction back = getOppositeDirection();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                Coord4D pointer = current.offset(right, x).offset(back, z);
                TileEntity pointerTile = pointer.getTileEntity(world);
                int corner = getCorner(x, z);
                if (corner != -1) {
                    if (!addSolarPanel(pointer.getTileEntity(world), corner)) {
                        if (pointer.offset(Direction.UP).getTileEntity(world) instanceof TileEntityThermalEvaporationBlock || !addTankPart(pointerTile)) {
                            return false;
                        }
                    }
                } else if ((x == 1 || x == 2) && (z == 1 || z == 2)) {
                    if (!pointer.isAirBlock(world)) {
                        return false;
                    }
                } else if (pointer.offset(Direction.UP).getTileEntity(world) instanceof TileEntityThermalEvaporationBlock || !addTankPart(pointerTile)) {
                    return false;
                }
            }
        }
        return true;
    }

    public int getMaxFluid() {
        return height * 4 * TankUpdateProtocol.FLUID_PER_TANK;
    }

    public int getCorner(int x, int z) {
        if (x == 0 && z == 0) {
            return 0;
        } else if (x == 0 && z == 3) {
            return 1;
        } else if (x == 3 && z == 0) {
            return 2;
        } else if (x == 3 && z == 3) {
            return 3;
        }
        return -1;
    }

    public boolean scanLowerLayer(Coord4D current) {
        Direction right = getRightSide();
        Direction back = getOppositeDirection();
        boolean foundCenter = false;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                Coord4D pointer = current.offset(right, x).offset(back, z);
                TileEntity pointerTile = pointer.getTileEntity(world);
                if ((x == 1 || x == 2) && (z == 1 || z == 2)) {
                    if (pointerTile instanceof TileEntityThermalEvaporationBlock) {
                        if (!foundCenter) {
                            if (x == 1 && z == 1) {
                                foundCenter = true;
                            } else {
                                height = -1;
                                return false;
                            }
                        }
                    } else if (foundCenter || !pointer.isAirBlock(world)) {
                        height = -1;
                        return false;
                    }
                } else if (!addTankPart(pointerTile)) {
                    height = -1;
                    return false;
                }
            }
        }

        height++;

        return !foundCenter;
    }

    public boolean addTankPart(TileEntity tile) {
        if (tile instanceof TileEntityThermalEvaporationBlock && (tile == this || !(tile instanceof TileEntityThermalEvaporationController))) {
            if (tile != this) {
                ((TileEntityThermalEvaporationBlock) tile).addToStructure(Coord4D.get(this));
                tankParts.add(Coord4D.get(tile));
            }
            return true;
        } else if (tile != this && tile instanceof TileEntityThermalEvaporationController) {
            controllerConflict = true;
        }
        return false;
    }

    public boolean addSolarPanel(TileEntity tile, int i) {
        if (tile != null && !tile.isRemoved()) {
            LazyOptionalHelper<IEvaporationSolar> capabilityHelper = CapabilityUtils.getCapabilityHelper(tile, Capabilities.EVAPORATION_SOLAR_CAPABILITY, Direction.DOWN);
            capabilityHelper.ifPresent(solar -> solars[i] = solar);
            return capabilityHelper.isPresent();
        }
        return false;
    }

    public int getScaledTempLevel(int i) {
        return (int) (i * Math.min(1, getTemperature() / MekanismConfig.general.evaporationMaxTemp.get()));
    }

    public Coord4D getRenderLocation() {
        if (!structured) {
            return null;
        }
        Direction right = getRightSide();
        Coord4D renderLocation = Coord4D.get(this).offset(right);
        renderLocation = isLeftOnFace ? renderLocation.offset(right) : renderLocation;
        renderLocation = renderLocation.offset(getLeftSide()).offset(getOppositeDirection());
        renderLocation.y = renderY;
        switch (getDirection()) {
            case SOUTH:
                renderLocation = renderLocation.offset(Direction.NORTH).offset(Direction.WEST);
                break;
            case WEST:
                renderLocation = renderLocation.offset(Direction.NORTH);
                break;
            case EAST:
                renderLocation = renderLocation.offset(Direction.WEST);
                break;
        }
        return renderLocation;
    }

    @Override
    public void handlePacketData(PacketBuffer dataStream) {
        super.handlePacketData(dataStream);
        if (isRemote()) {
            TileUtils.readTankData(dataStream, inputTank);
            TileUtils.readTankData(dataStream, outputTank);

            structured = dataStream.readBoolean();
            controllerConflict = dataStream.readBoolean();
            clientSolarAmount = dataStream.readInt();
            height = dataStream.readInt();
            temperature = dataStream.readFloat();
            biomeTemp = dataStream.readFloat();
            isLeftOnFace = dataStream.readBoolean();
            lastGain = dataStream.readFloat();
            totalLoss = dataStream.readFloat();
            renderY = dataStream.readInt();

            if (structured != clientStructured) {
                inputTank.setCapacity(getMaxFluid());
                MekanismUtils.updateBlock(getWorld(), getPos());
                if (structured) {
                    // Calculate the two corners of the evap tower using the render location as basis (which is the
                    // lowest rightmost corner inside the tower, relative to the controller).
                    BlockPos corner1 = getRenderLocation().getPos().offset(Direction.WEST).offset(Direction.NORTH).down();
                    BlockPos corner2 = corner1.offset(Direction.EAST, 3).offset(Direction.SOUTH, 3).up(height - 1);
                    // Use the corners to spin up the sparkle
                    Mekanism.proxy.doMultiblockSparkle(this, corner1, corner2, tile -> tile instanceof TileEntityThermalEvaporationBlock);
                }
                clientStructured = structured;
            }
        }
    }

    @Override
    public TileNetworkList getNetworkedData(TileNetworkList data) {
        super.getNetworkedData(data);
        TileUtils.addTankData(data, inputTank);
        TileUtils.addTankData(data, outputTank);
        data.add(structured);
        data.add(controllerConflict);
        data.add(getActiveSolars());
        data.add(height);
        data.add(temperature);
        data.add(biomeTemp);
        data.add(isLeftOnFace);
        data.add(lastGain);
        data.add(totalLoss);
        data.add(renderY);
        return data;
    }

    @Override
    public void read(CompoundNBT nbtTags) {
        super.read(nbtTags);
        inputTank.readFromNBT(nbtTags.getCompound("waterTank"));
        outputTank.readFromNBT(nbtTags.getCompound("brineTank"));

        temperature = nbtTags.getFloat("temperature");
    }

    @Nonnull
    @Override
    public CompoundNBT write(CompoundNBT nbtTags) {
        super.write(nbtTags);
        nbtTags.put("waterTank", inputTank.writeToNBT(new CompoundNBT()));
        nbtTags.put("brineTank", outputTank.writeToNBT(new CompoundNBT()));

        nbtTags.putFloat("temperature", temperature);
        return nbtTags;
    }

    @Override
    public TileEntityThermalEvaporationController getController() {
        return structured ? this : null;
    }

    public void clearStructure() {
        for (Coord4D tankPart : tankParts) {
            TileEntity tile = tankPart.getTileEntity(world);
            if (tile instanceof TileEntityThermalEvaporationBlock) {
                ((TileEntityThermalEvaporationBlock) tile).controllerGone();
            }
        }
        tankParts.clear();
        solars = new IEvaporationSolar[]{null, null, null, null};
    }

    @Nonnull
    @Override
    @OnlyIn(Dist.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return INFINITE_EXTENT_AABB;
    }

    @Override
    public boolean getActive() {
        return structured;
    }

    @Override
    public void setActive(boolean active) {
    }

    @Override
    public boolean renderUpdate() {
        return true;
    }

    @Override
    public Object[] getTanks() {
        return new Object[]{inputTank, outputTank};
    }

    @Override
    public boolean isCapabilityDisabled(@Nonnull Capability<?> capability, Direction side) {
        //TODO: Should this be disabled via the inventory slots instead. (Then we can't access the items when opening the controller)
        if (!structured && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return true;
        }
        return super.isCapabilityDisabled(capability, side);
    }
}