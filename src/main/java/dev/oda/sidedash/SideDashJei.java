package dev.oda.sidedash;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** JEI bridge. Only JEI instantiates this class, and callers check ModList.isLoaded("jei") first,
 *  so the mod still works without JEI installed. */
@JeiPlugin
public final class SideDashJei implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(SideDash.ID, "dashboard"); }
    @Override public void onRuntimeAvailable(IJeiRuntime r) { runtime = r; }
    @Override public void onRuntimeUnavailable() { runtime = null; }

    /** Recipes that make the item (uses=false) or use it as an ingredient (uses=true), in JEI's order. */
    static List<IRecipeLayoutDrawable<?>> layouts(ItemStack stack, boolean uses, int limit) {
        IJeiRuntime rt = runtime;
        List<IRecipeLayoutDrawable<?>> out = new ArrayList<>();
        if (rt == null) return out;
        IFocusFactory ff = rt.getJeiHelpers().getFocusFactory();
        IFocus<ItemStack> focus = ff.createFocus(uses ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack);
        IFocusGroup group = ff.createFocusGroup(List.of(focus));
        IRecipeManager rm = rt.getRecipeManager();
        rm.createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
                .forEach(cat -> add(rm, cat, focus, group, out, limit));
        return out;
    }

    private static <T> void add(IRecipeManager rm, IRecipeCategory<T> cat, IFocus<?> focus, IFocusGroup group,
                                List<IRecipeLayoutDrawable<?>> out, int limit) {
        if (out.size() >= limit) return;
        rm.createRecipeLookup(cat.getRecipeType()).limitFocus(List.of(focus)).get()
                .limit(limit - out.size())
                .forEach(r -> rm.createRecipeLayoutDrawable(cat, r, group).ifPresent(out::add));
    }
}
