package mekanism.client.gui;

import java.io.IOException;
import mekanism.api.TileNetworkList;
import mekanism.client.gui.element.GuiRedstoneControl;
import mekanism.client.gui.element.gauge.GuiGauge.Type;
import mekanism.client.gui.element.gauge.GuiNumberGauge;
import mekanism.client.gui.element.gauge.GuiNumberGauge.INumberInfoHandler;
import mekanism.client.gui.element.tab.GuiAmplifierTab;
import mekanism.client.gui.element.tab.GuiSecurityTab;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.Mekanism;
import mekanism.common.inventory.container.ContainerLaserAmplifier;
import mekanism.common.network.PacketTileEntity.TileEntityMessage;
import mekanism.common.tile.TileEntityLaserAmplifier;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.input.Keyboard;

@OnlyIn(Dist.CLIENT)
public class GuiLaserAmplifier extends GuiMekanismTile<TileEntityLaserAmplifier> {

    private TextFieldWidget minField;
    private TextFieldWidget maxField;
    private TextFieldWidget timerField;

    public GuiLaserAmplifier(PlayerInventory inventory, TileEntityLaserAmplifier tile) {
        super(tile, new ContainerLaserAmplifier(inventory, tile));
        ResourceLocation resource = getGuiLocation();
        addGuiElement(new GuiNumberGauge(new INumberInfoHandler() {
            @Override
            public TextureAtlasSprite getIcon() {
                return MekanismRenderer.energyIcon;
            }

            @Override
            public double getLevel() {
                return tileEntity.collectedEnergy;
            }

            @Override
            public double getMaxLevel() {
                return TileEntityLaserAmplifier.MAX_ENERGY;
            }

            @Override
            public String getText(double level) {
                return LangUtils.localize("gui.storing") + ": " + MekanismUtils.getEnergyDisplay(level, tileEntity.getMaxEnergy());
            }
        }, Type.STANDARD, this, resource, 6, 10));
        addGuiElement(new GuiSecurityTab(this, tileEntity, resource));
        addGuiElement(new GuiRedstoneControl(this, tileEntity, resource));
        addGuiElement(new GuiAmplifierTab(this, tileEntity, resource));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(tileEntity.getName(), 55, 6, 0x404040);
        fontRenderer.drawString(LangUtils.localize("container.inventory"), 8, (ySize - 96) + 2, 0x404040);
        fontRenderer.drawString(tileEntity.time > 0 ? LangUtils.localize("gui.delay") + ": " + tileEntity.time + "t"
                                                    : LangUtils.localize("gui.noDelay"), 26, 30, 0x404040);
        fontRenderer.drawString(LangUtils.localize("gui.min") + ": " + MekanismUtils.getEnergyDisplay(tileEntity.minThreshold), 26, 45, 0x404040);
        fontRenderer.drawString(LangUtils.localize("gui.max") + ": " + MekanismUtils.getEnergyDisplay(tileEntity.maxThreshold), 26, 60, 0x404040);
        super.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(int xAxis, int yAxis) {
        super.drawGuiContainerBackgroundLayer(xAxis, yAxis);
        minField.drawTextBox();
        maxField.drawTextBox();
        timerField.drawTextBox();
        MekanismRenderer.resetColor();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        minField.updateCursorCounter();
        maxField.updateCursorCounter();
        timerField.updateCursorCounter();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        minField.mouseClicked(mouseX, mouseY, button);
        maxField.mouseClicked(mouseX, mouseY, button);
        timerField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected ResourceLocation getGuiLocation() {
        return MekanismUtils.getResource(ResourceType.GUI, "GuiBlank.png");
    }

    @Override
    public void keyTyped(char c, int i) throws IOException {
        if (!(minField.isFocused() || maxField.isFocused() || timerField.isFocused()) || i == Keyboard.KEY_ESCAPE) {
            super.keyTyped(c, i);
        }

        if (i == Keyboard.KEY_RETURN) {
            if (minField.isFocused()) {
                setMinThreshold();
            }
            if (maxField.isFocused()) {
                setMaxThreshold();
            }
            if (timerField.isFocused()) {
                setTime();
            }
        }

        if (Character.isDigit(c) || c == '.' || c == 'E' || isTextboxKey(c, i)) {
            minField.textboxKeyTyped(c, i);
            maxField.textboxKeyTyped(c, i);
            if (c != '.' && c != 'E') {
                timerField.textboxKeyTyped(c, i);
            }
        }
    }

    private void setMinThreshold() {
        if (!minField.getText().isEmpty()) {
            double toUse;
            try {
                toUse = Math.max(0, Double.parseDouble(minField.getText()));
            } catch (Exception e) {
                minField.setText("");
                return;
            }
            TileNetworkList data = TileNetworkList.withContents(0, toUse);
            Mekanism.packetHandler.sendToServer(new TileEntityMessage(tileEntity, data));
            minField.setText("");
        }
    }

    private void setMaxThreshold() {
        if (!maxField.getText().isEmpty()) {
            double toUse;
            try {
                toUse = Math.max(0, Double.parseDouble(maxField.getText()));
            } catch (Exception e) {
                maxField.setText("");
                return;
            }
            TileNetworkList data = TileNetworkList.withContents(1, toUse);
            Mekanism.packetHandler.sendToServer(new TileEntityMessage(tileEntity, data));
            maxField.setText("");
        }
    }

    private void setTime() {
        if (!timerField.getText().isEmpty()) {
            int toUse = Math.max(0, Integer.parseInt(timerField.getText()));
            TileNetworkList data = TileNetworkList.withContents(2, toUse);
            Mekanism.packetHandler.sendToServer(new TileEntityMessage(tileEntity, data));
            timerField.setText("");
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        String prevTime = timerField != null ? timerField.getText() : "";
        timerField = new TextFieldWidget(0, fontRenderer, guiLeft + 96, guiTop + 28, 36, 11);
        timerField.setMaxStringLength(4);
        timerField.setText(prevTime);

        String prevMin = minField != null ? minField.getText() : "";
        minField = new TextFieldWidget(1, fontRenderer, guiLeft + 96, guiTop + 43, 72, 11);
        minField.setMaxStringLength(10);
        minField.setText(prevMin);

        String prevMax = maxField != null ? maxField.getText() : "";
        maxField = new TextFieldWidget(2, fontRenderer, guiLeft + 96, guiTop + 58, 72, 11);
        maxField.setMaxStringLength(10);
        maxField.setText(prevMax);
    }
}