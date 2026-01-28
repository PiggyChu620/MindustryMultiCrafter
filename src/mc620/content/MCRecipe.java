package mc620.content;

import arc.struct.Seq;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;

/** One recipe entry for {@link MultiCrafter}. */
public class MCRecipe
{
    public String name;

    /** Craft time in ticks. */
    public float craftTime = MCUnits.seconds(1f);

    /** Inputs removed on craft completion (like vanilla crafters). */
    public ItemStack[] itemInputs = new ItemStack[0];
    public LiquidStack[] liquidInputs = new LiquidStack[0];

    /** Outputs produced; liquids are produced continuously while running (like GenericCrafter). */
    public ItemStack[] itemOutputs = new ItemStack[0];
    public LiquidStack[] liquidOutputs = new LiquidStack[0];

    /** Power use/production in power-per-tick. */
    public float powerUse = 0f;
    public float powerProduce = 0f;

    /** Heat requirement for 100% efficiency. 0 disables heat input. */
    public float heatRequirement = 0f;

    /** Heat output when running. 0 disables heat output. */
    public float heatOutput = 0f;

    // Optional visuals.
    public Effect craftEffect = Fx.none;
    public Effect updateEffect = Fx.none;
    public float updateEffectChance = 0.04f;
    public float updateEffectSpread = 4f;

    /** Warmup lerp rate (approachDelta). */
    public float warmupSpeed = 0.019f;

    public MCRecipe()
    {
    }

    public static Builder builder(String name)
    {
        return new Builder(name);
    }

    public static class Builder
    {
        private final MCRecipe recipe;

        private final Seq<ItemStack> itemInputs = new Seq<ItemStack>();
        private final Seq<LiquidStack> liquidInputs = new Seq<LiquidStack>();
        private final Seq<ItemStack> itemOutputs = new Seq<ItemStack>();
        private final Seq<LiquidStack> liquidOutputs = new Seq<LiquidStack>();

        public Builder(String name)
        {
            recipe = new MCRecipe();
            recipe.name = name;
        }

        public Builder craftTimeSeconds(float seconds)
        {
            recipe.craftTime = MCUnits.seconds(seconds);
            return this;
        }

        public Builder itemIn(Item item, int amount)
        {
            itemInputs.add(new ItemStack(item, amount));
            return this;
        }

        public Builder itemOut(Item item, int amount)
        {
            itemOutputs.add(new ItemStack(item, amount));
            return this;
        }

        /** Liquid input rate in units/second. */
        public Builder liquidInPerSecond(Liquid liquid, float amountPerSecond)
        {
            liquidInputs.add(new LiquidStack(liquid, MCUnits.perSecond(amountPerSecond)));
            return this;
        }

        /** Liquid output rate in units/second (produced continuously while running). */
        public Builder liquidOutPerSecond(Liquid liquid, float amountPerSecond)
        {
            liquidOutputs.add(new LiquidStack(liquid, MCUnits.perSecond(amountPerSecond)));
            return this;
        }

        /** Power use in units/second. */
        public Builder powerUsePerSecond(float powerPerSecond)
        {
            recipe.powerUse = MCUnits.perSecond(powerPerSecond);
            return this;
        }

        /** Power production in units/second. */
        public Builder powerProducePerSecond(float powerPerSecond)
        {
            recipe.powerProduce = MCUnits.perSecond(powerPerSecond);
            return this;
        }

        public Builder heatRequirement(float heat)
        {
            recipe.heatRequirement = heat;
            return this;
        }

        public Builder heatOutput(float heat)
        {
            recipe.heatOutput = heat;
            return this;
        }

        public Builder craftEffect(Effect effect)
        {
            recipe.craftEffect = effect;
            return this;
        }

        public Builder updateEffect(Effect effect, float chance)
        {
            recipe.updateEffect = effect;
            recipe.updateEffectChance = chance;
            return this;
        }

        public Builder warmupSpeed(float speed)
        {
            recipe.warmupSpeed = speed;
            return this;
        }

        public MCRecipe build()
        {
            recipe.itemInputs = itemInputs.toArray(ItemStack.class);
            recipe.liquidInputs = liquidInputs.toArray(LiquidStack.class);
            recipe.itemOutputs = itemOutputs.toArray(ItemStack.class);
            recipe.liquidOutputs = liquidOutputs.toArray(LiquidStack.class);
            return recipe;
        }
    }
}
