package mekanism.common.util;

import javax.annotation.Nonnull;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.TextFormatting;

/**
 * Created by Thiakil on 8/11/2017.
 */
public class TextComponentGroup extends StringTextComponent {

    public TextComponentGroup() {
        super("");
    }

    public TextComponentGroup(TextFormatting color) {
        super("");
        getStyle().setColor(color);
    }

    @Nonnull
    @Override
    public String getUnformattedComponentText() {
        return "";
    }

    @Nonnull
    @Override
    public TextComponentGroup createCopy() {
        TextComponentGroup textcomponentstring = new TextComponentGroup();
        textcomponentstring.setStyle(this.getStyle().createShallowCopy());
        for (ITextComponent itextcomponent : this.getSiblings()) {
            textcomponentstring.appendSibling(itextcomponent.createCopy());
        }
        return textcomponentstring;
    }

    public TextComponentGroup string(String s) {
        this.appendSibling(new StringTextComponent(s));
        return this;
    }

    public TextComponentGroup string(String s, TextFormatting color) {
        ITextComponent t = new StringTextComponent(s);
        t.getStyle().setColor(color);
        this.appendSibling(t);
        return this;
    }

    public TextComponentGroup translation(String key) {
        this.appendSibling(new TranslationTextComponent(key));
        return this;
    }

    public TextComponentGroup translation(String key, Object... args) {
        this.appendSibling(new TranslationTextComponent(key, args));
        return this;
    }

    public TextComponentGroup translation(String key, TextFormatting color) {
        ITextComponent t = new TranslationTextComponent(key);
        t.getStyle().setColor(color);
        this.appendSibling(t);
        return this;
    }

    public TextComponentGroup translation(String key, TextFormatting color, Object... args) {
        ITextComponent t = new TranslationTextComponent(key, args);
        t.getStyle().setColor(color);
        this.appendSibling(t);
        return this;
    }

    public TextComponentGroup component(ITextComponent component) {
        this.appendSibling(component);
        return this;
    }

    public TextComponentGroup component(ITextComponent component, TextFormatting color) {
        component.getStyle().setColor(color);
        this.appendSibling(component);
        return this;
    }
}