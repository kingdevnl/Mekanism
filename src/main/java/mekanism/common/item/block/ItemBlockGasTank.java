package mekanism.common.item.block;

import java.util.List;
import javax.annotation.Nonnull;
import mekanism.api.EnumColor;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import mekanism.client.MekanismClient;
import mekanism.common.block.BlockGasTank;
import mekanism.common.config.MekanismConfig;
import mekanism.common.item.IItemSustainedInventory;
import mekanism.common.security.ISecurityItem;
import mekanism.common.tier.GasTankTier;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.LangUtils;
import mekanism.common.util.SecurityUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class ItemBlockGasTank extends ItemBlockTooltip implements IGasItem, IItemSustainedInventory, ISecurityItem {

    /**
     * The maximum amount of gas this tank can hold.
     */
    public int MAX_GAS = 96000;

    public ItemBlockGasTank(BlockGasTank block) {
        super(block);
        setMaxStackSize(1);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addInformation(@Nonnull ItemStack itemstack, World world, @Nonnull List<String> list, @Nonnull ITooltipFlag flag) {
        GasStack gasStack = getGas(itemstack);
        if (gasStack == null) {
            list.add(EnumColor.DARK_RED + LangUtils.localize("gui.empty") + ".");
        } else {
            String amount = gasStack.amount == Integer.MAX_VALUE ? LangUtils.localize("gui.infinite") : Integer.toString(gasStack.amount);
            list.add(EnumColor.ORANGE + gasStack.getGas().getLocalizedName() + ": " + EnumColor.GREY + amount);
        }
        int cap = getTier(itemstack).getStorage();
        list.add(EnumColor.INDIGO + LangUtils.localize("tooltip.capacity") + ": " + EnumColor.GREY + (cap == Integer.MAX_VALUE ? LangUtils.localize("gui.infinite") : cap));
        super.addInformation(itemstack, world, list, flag);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addDescription(@Nonnull ItemStack itemstack, World world, @Nonnull List<String> list, @Nonnull ITooltipFlag flag) {
        list.add(SecurityUtils.getOwnerDisplay(Minecraft.getInstance().player, MekanismClient.clientUUIDMap.get(getOwnerUUID(itemstack))));
        list.add(EnumColor.GREY + LangUtils.localize("gui.security") + ": " + SecurityUtils.getSecurityDisplay(itemstack, Dist.CLIENT));
        if (SecurityUtils.isOverridden(itemstack, Dist.CLIENT)) {
            list.add(EnumColor.RED + "(" + LangUtils.localize("gui.overridden") + ")");
        }
        list.add(EnumColor.AQUA + LangUtils.localize("tooltip.inventory") + ": " + EnumColor.GREY +
                 LangUtils.transYesNo(getInventory(itemstack) != null && getInventory(itemstack).tagCount() != 0));
    }

    @Override
    public GasStack getGas(ItemStack itemstack) {
        return GasStack.readFromNBT(ItemDataUtils.getCompound(itemstack, "stored"));
    }

    @Override
    public void setGas(ItemStack itemstack, GasStack stack) {
        if (stack == null || stack.amount == 0) {
            ItemDataUtils.removeData(itemstack, "stored");
        } else {
            int amount = Math.max(0, Math.min(stack.amount, getMaxGas(itemstack)));
            GasStack gasStack = new GasStack(stack.getGas(), amount);
            ItemDataUtils.setCompound(itemstack, "stored", gasStack.write(new CompoundNBT()));
        }
    }

    public ItemStack getEmptyItem() {
        ItemStack empty = new ItemStack(this);
        setGas(empty, null);
        return empty;
    }

    private GasTankTier getTier(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemBlockGasTank) {
            BlockGasTank gasTank = (BlockGasTank) (((ItemBlockGasTank) item).block);
            return gasTank.getTier();
        }
        return GasTankTier.BASIC;
    }

    @Override
    public void getSubItems(@Nonnull ItemGroup tabs, @Nonnull NonNullList<ItemStack> list) {
        if (!isInCreativeTab(tabs)) {
            return;
        }
        list.add(getEmptyItem());
        BlockGasTank gasTank = (BlockGasTank) this.block;
        if (gasTank.getTier() == GasTankTier.CREATIVE && MekanismConfig.current().general.prefilledGasTanks.val()) {
            for (Gas type : GasRegistry.getRegisteredGasses()) {
                if (type.isVisible()) {
                    ItemStack filled = new ItemStack(this);
                    setGas(filled, new GasStack(type, ((IGasItem) filled.getItem()).getMaxGas(filled)));
                    list.add(filled);
                }
            }
        }
    }

    @Override
    public int getMaxGas(ItemStack itemstack) {
        return getTier(itemstack).getStorage();
    }

    @Override
    public int getRate(ItemStack itemstack) {
        return getTier(itemstack).getOutput();
    }

    @Override
    public int addGas(ItemStack itemstack, GasStack stack) {
        if (getGas(itemstack) != null && getGas(itemstack).getGas() != stack.getGas()) {
            return 0;
        }
        if (getTier(itemstack) == GasTankTier.CREATIVE) {
            setGas(itemstack, new GasStack(stack.getGas(), Integer.MAX_VALUE));
            return stack.amount;
        }
        int toUse = Math.min(getMaxGas(itemstack) - getStored(itemstack), Math.min(getRate(itemstack), stack.amount));
        setGas(itemstack, new GasStack(stack.getGas(), getStored(itemstack) + toUse));
        return toUse;
    }

    @Override
    public GasStack removeGas(ItemStack itemstack, int amount) {
        if (getGas(itemstack) == null) {
            return null;
        }
        Gas type = getGas(itemstack).getGas();
        int gasToUse = Math.min(getStored(itemstack), Math.min(getRate(itemstack), amount));
        if (getTier(itemstack) != GasTankTier.CREATIVE) {
            setGas(itemstack, new GasStack(type, getStored(itemstack) - gasToUse));
        }
        return new GasStack(type, gasToUse);
    }

    private int getStored(ItemStack itemstack) {
        return getGas(itemstack) != null ? getGas(itemstack).amount : 0;
    }

    @Override
    public boolean canReceiveGas(ItemStack itemstack, Gas type) {
        return getGas(itemstack) == null || getGas(itemstack).getGas() == type;
    }

    @Override
    public boolean canProvideGas(ItemStack itemstack, Gas type) {
        return getGas(itemstack) != null && (type == null || getGas(itemstack).getGas() == type);
    }

    @Override
    public boolean showDurabilityBar(ItemStack stack) {
        return getGas(stack) != null; // No bar for empty containers as bars are drawn on top of stack count number
    }

    @Override
    public double getDurabilityForDisplay(ItemStack stack) {
        return 1D - ((getGas(stack) != null ? (double) getGas(stack).amount : 0D) / (double) getMaxGas(stack));
    }

    @Override
    public int getRGBDurabilityForDisplay(@Nonnull ItemStack stack) {
        return MathHelper.hsvToRGB(Math.max(0.0F, (float) (1 - getDurabilityForDisplay(stack))) / 3.0F, 1.0F, 1.0F);
    }
}