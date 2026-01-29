package mc620.content;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Eachable;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Icon;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.consumers.ConsumeItemDynamic;
import mindustry.world.consumers.ConsumeLiquidsDynamic;
import mindustry.world.consumers.ConsumePowerDynamic;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;

/**
 * MultiCrafter: multiple selectable recipes in one block.
 *
 * Notes:
 * - Inputs are consumed on craft completion (like GenericCrafter).
 * - Liquid outputs are produced continuously while running.
 * - Power use/production and heat requirement/output can vary per recipe.
 */
public class MultiCrafter extends Block
{
    private static final int ROUTE_TYPE_ITEM = 0;
    private static final int ROUTE_TYPE_LIQUID = 1;

    public final Seq<MCRecipe> recipes = new Seq<MCRecipe>();

    /** Liquid output directions, same meaning as GenericCrafter. -1 dumps all sides. */
    public int[] liquidOutputDirections = {-1};

    /** Item output directions aligned with recipe itemOutputs. -1 dumps all sides. */
    public int[] itemOutputDirections = {-1};

    /** If true, multi-liquid output blocks dump excess when at least one liquid has space. */
    public boolean dumpExtraLiquid = true;

    /** If true, ignore output liquid fullness when crafting (dangerous; matches GenericCrafter option). */
    public boolean ignoreLiquidFullness = false;

    /** Capacity multipliers derived from per-recipe maxima. */
    public float itemCapacityMultiplier = 4f;
    public float liquidCapacityMultiplier = 2f;

    /** Heat: after meeting requirement, extra heat scales efficiency by this number. */
    public float overheatScale = 1f;

    /** Heat: maximum possible efficiency from heat scaling. */
    public float maxHeatEfficiency = 4f;

    /** Heat output warmup rate (approachDelta), similar to HeatProducer. */
    public float heatOutputWarmupRate = 0.15f;

    /** Optional global effects used when recipe doesn't specify its own. */
    public Effect defaultCraftEffect = Fx.none;
    public Effect defaultUpdateEffect = Fx.none;
    public float defaultUpdateEffectChance = 0.04f;
    public float defaultUpdateEffectSpread = 4f;

    public DrawBlock drawer = new DrawDefault();

    // cached unions for dumping
    private Item[] allOutputItems = new Item[0];
    private Liquid[] allOutputLiquids = new Liquid[0];

    public MultiCrafter(String name)
    {
        super(name);

        update = true;
        solid = true;
        sync = true;
        hasItems = true;
        configurable = true;
        saveConfig = true;

        ambientSound = mindustry.gen.Sounds.loopMachine;
        ambientSoundVolume = 0.03f;
        drawArrow = false;
    }

    @Override
    public void load()
    {
        super.load();
        drawer.load(this);
    }

    @Override
    public TextureRegion[] icons()
    {
        return drawer.finalIcons(this);
    }

    @Override
    public void init()
    {
        if(recipes.isEmpty())
        {
            throw new IllegalStateException("MultiCrafter '" + name + "' has no recipes.");
        }

        // Dynamic consumers based on current recipe.
        consume(new ConsumeItemDynamic((MultiCrafterBuild b) -> b.currentRecipe().itemInputs));
        consume(new ConsumeLiquidsDynamic((MultiCrafterBuild b) -> b.currentRecipe().liquidInputs));

        float maxPowerUse = 0f;
        boolean anyPower = false;
        boolean anyLiquids = false;

        boolean anyHeatIn = false;
        boolean anyHeatOut = false;

        int maxItemStack = 0;
        float maxLiquidRate = 0f;

        ObjectSet<Item> outItems = new ObjectSet<Item>();
        ObjectSet<Liquid> outLiquids = new ObjectSet<Liquid>();

        for(int i = 0; i < recipes.size; i++)
        {
            MCRecipe r = recipes.get(i);

            if(r.itemInputs == null) r.itemInputs = new ItemStack[0];
            if(r.itemOutputs == null) r.itemOutputs = new ItemStack[0];
            if(r.liquidInputs == null) r.liquidInputs = new LiquidStack[0];
            if(r.liquidOutputs == null) r.liquidOutputs = new LiquidStack[0];

            for(int j = 0; j < r.itemInputs.length; j++)
            {
                maxItemStack = Math.max(maxItemStack, r.itemInputs[j].amount);
            }
            for(int j = 0; j < r.itemOutputs.length; j++)
            {
                maxItemStack = Math.max(maxItemStack, r.itemOutputs[j].amount);
                outItems.add(r.itemOutputs[j].item);
            }

            for(int j = 0; j < r.liquidInputs.length; j++)
            {
                maxLiquidRate = Math.max(maxLiquidRate, r.liquidInputs[j].amount);
                anyLiquids = true;
            }
            for(int j = 0; j < r.liquidOutputs.length; j++)
            {
                maxLiquidRate = Math.max(maxLiquidRate, r.liquidOutputs[j].amount);
                outLiquids.add(r.liquidOutputs[j].liquid);
                anyLiquids = true;
            }

            maxPowerUse = Math.max(maxPowerUse, r.powerUse);
            if(r.powerUse > 0f || r.powerProduce > 0f)
            {
                anyPower = true;
            }

            if(r.heatRequirement > 0f)
            {
                anyHeatIn = true;
            }

            if(r.heatOutput > 0f)
            {
                anyHeatOut = true;
            }
        }

        if(anyPower)
        {
            hasPower = true;
            // show max power use in stats, but request actual per-recipe usage.
            consume(new ConsumePowerDynamic(maxPowerUse, (Building build) -> ((MultiCrafterBuild)build).currentRecipe().powerUse));
            outputsPower = true;
        }

        hasLiquids = anyLiquids;
        outputsLiquid = anyLiquids;

        // capacity sizing
        if(maxItemStack > 0)
        {
            itemCapacity = Math.max(itemCapacity, Math.max(10, Mathf.ceil(maxItemStack * itemCapacityMultiplier)));
        }

        if(anyLiquids)
        {
            // maxLiquidRate is per-tick; multiply by 60 for ~1 second buffer, then scale.
            liquidCapacity = Math.max(liquidCapacity, (maxLiquidRate * 60f) * liquidCapacityMultiplier);
            if(liquidCapacity < 30f) liquidCapacity = 30f;
        }

        // cache union outputs for dumping
        allOutputItems = outItems.toSeq().toArray(Item.class);
        allOutputLiquids = outLiquids.toSeq().toArray(Liquid.class);

        // config
        config(Integer.class, (MultiCrafterBuild build, Integer value) -> {
            if(value == null) return;
            build.setRecipeIndex(value.intValue());
        });

        config(Point2.class, (MultiCrafterBuild build, Point2 value) -> {
            if(value == null) return;
            build.setRoute(value.x, value.y);
        });

        configClear((MultiCrafterBuild build) -> build.setRecipeIndex(0));

        super.init();

        if(anyHeatIn || anyHeatOut)
        {
            // heat blocks are typically non-overdriveable; but leaving this enabled is okay.
            // If you prefer strict vanilla behavior, set canOverdrive = false in your block config.
        }
    }

    @Override
    public void setStats()
    {
        super.setStats();
        stats.add(Stat.productionTime, "Multiple");

        // show max heat requirement/output to hint players
        float maxHeatReq = 0f;
        float maxHeatOut = 0f;
        for(int i = 0; i < recipes.size; i++)
        {
            maxHeatReq = Math.max(maxHeatReq, recipes.get(i).heatRequirement);
            maxHeatOut = Math.max(maxHeatOut, recipes.get(i).heatOutput);
        }

        if(maxHeatReq > 0f)
        {
            stats.add(Stat.input, maxHeatReq, StatUnit.heatUnits);
        }

        if(maxHeatOut > 0f)
        {
            stats.add(Stat.output, maxHeatOut, StatUnit.heatUnits);
        }

        // Detailed per-recipe I/O stats for the block info (F1).
        stats.add(Stat.output, table -> {
            table.row();
            for(int i = 0; i < recipes.size; i++)
            {
                final int idx = i;
                final MCRecipe r = recipes.get(i);
                final float craftTime = Math.max(r.craftTime, 1f);

                table.table(Styles.grayPanel, t -> {
                    t.left().top().defaults().left();

                    String title = r.name == null ? "Recipe " + (idx + 1) : localizeRecipeName(r.name);
                    t.add("[accent]" + title).row();

                    t.table(in -> {
                        in.left().top();
                        in.add("[lightgray]Inputs").row();

                        boolean anyIn = false;

                        if(r.itemInputs != null)
                        {
                            for(int j = 0; j < r.itemInputs.length; j++)
                            {
                                ItemStack stack = r.itemInputs[j];
                                in.add(StatValues.displayItem(stack.item, stack.amount, craftTime, true)).padRight(5).row();
                                anyIn = true;
                            }
                        }

                        if(r.liquidInputs != null)
                        {
                            for(int j = 0; j < r.liquidInputs.length; j++)
                            {
                                LiquidStack stack = r.liquidInputs[j];
                                in.add(StatValues.displayLiquid(stack.liquid, stack.amount * 60f, true)).padRight(5).row();
                                anyIn = true;
                            }
                        }

                        if(!anyIn)
                        {
                            in.add("[lightgray]None");
                        }
                    }).padRight(12);

                    t.table(out -> {
                        out.left().top();
                        out.add("[lightgray]Outputs").row();

                        boolean anyOut = false;

                        if(r.itemOutputs != null)
                        {
                            for(int j = 0; j < r.itemOutputs.length; j++)
                            {
                                ItemStack stack = r.itemOutputs[j];
                                out.add(StatValues.displayItem(stack.item, stack.amount, craftTime, true)).padRight(5).row();
                                anyOut = true;
                            }
                        }

                        if(r.liquidOutputs != null)
                        {
                            for(int j = 0; j < r.liquidOutputs.length; j++)
                            {
                                LiquidStack stack = r.liquidOutputs[j];
                                out.add(StatValues.displayLiquid(stack.liquid, stack.amount * 60f, true)).padRight(5).row();
                                anyOut = true;
                            }
                        }

                        if(!anyOut)
                        {
                            out.add("[lightgray]None");
                        }
                    });
                }).growX().pad(5);

                table.row();
            }
        });
    }

    @Override
    public void setBars()
    {
        super.setBars();

        // Standard progress bar.
        addBar("progress", (MultiCrafterBuild b) ->
            new Bar(() -> Core.bundle.get("bar.progress"), () -> Pal.ammo, () -> b.progress()));

        // Heat input bar (if any recipe uses heat).
        addBar("heat", (MultiCrafterBuild b) ->
            new Bar(() -> {
                float req = b.currentRecipe().heatRequirement;
                if(req <= 0f) return Core.bundle.get("bar.heat");
                return Core.bundle.format("bar.heatpercent", (int)(b.heatIn + 0.01f), (int)(b.efficiencyScale() * 100f + 0.01f));
            }, () -> Pal.lightOrange, () -> {
                float req = b.currentRecipe().heatRequirement;
                if(req <= 0f) return 0f;
                return b.heatIn / req;
            }));

        // Heat output bar (if any recipe outputs heat).
        addBar("heat-out", (MultiCrafterBuild b) ->
            new Bar(() -> Core.bundle.get("bar.heat"), () -> Pal.lightOrange, () -> {
                float out = b.currentRecipe().heatOutput;
                if(out <= 0f) return 0f;
                return b.heatOut / out;
            }));
    }

    @Override
    public boolean rotatedOutput(int fromX, int fromY, Tile destination)
    {
        if(!(destination.build instanceof ConduitBuild)) return false;

        Building crafter = Vars.world.build(fromX, fromY);
        if(crafter == null) return false;

        int relative = Mathf.mod(crafter.relativeTo(destination) - crafter.rotation, 4);
        for(int i = 0; i < liquidOutputDirections.length; i++)
        {
            int dir = liquidOutputDirections[i];
            if(dir == -1 || dir == relative) return false;
        }

        return true;
    }

    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list)
    {
        drawer.drawPlan(this, plan, list);
    }

    // Configuration UI is defined on the building, not the block.

    private String localizeRecipeName(String name)
    {
        // If bundle has key, use it; else show raw name.
        if(Core.bundle.has(name))
        {
            return Core.bundle.get(name);
        }
        return name;
    }

    private TextureRegion pickRecipeIcon(MCRecipe r)
    {
        if(r.itemOutputs != null && r.itemOutputs.length > 0)
        {
            return r.itemOutputs[0].item.uiIcon;
        }
        if(r.liquidOutputs != null && r.liquidOutputs.length > 0)
        {
            return r.liquidOutputs[0].liquid.uiIcon;
        }
        if(r.itemInputs != null && r.itemInputs.length > 0)
        {
            return r.itemInputs[0].item.uiIcon;
        }
        if(r.liquidInputs != null && r.liquidInputs.length > 0)
        {
            return r.liquidInputs[0].liquid.uiIcon;
        }
        return region;
    }

    private static int packRoute(int type, int outputIndex, int dir)
    {
        return (type << 10) | ((outputIndex & 0xFF) << 2) | (dir & 0x3);
    }

    private static int routeType(int packed)
    {
        return (packed >> 10) & 0x3;
    }

    private static int routeIndex(int packed)
    {
        return (packed >> 2) & 0xFF;
    }

    private static int routeDir(int packed)
    {
        return packed & 0x3;
    }

    private static TextureRegionDrawable dirIcon(int dir)
    {
        switch(dir)
        {
            case 0: return Icon.rightOpen;
            case 1: return Icon.upOpen;
            case 2: return Icon.leftOpen;
            case 3: return Icon.downOpen;
            default: return Icon.cancel;
        }
    }

    public class MultiCrafterBuild extends Building implements HeatConsumer, HeatBlock
    {
        public int recipeIndex = 0;

        public float progress = 0f;
        public float totalProgress = 0f;
        public float warmup = 0f;

        // heat input
        public float[] sideHeat = new float[4];
        public float heatIn = 0f;

        // heat output
        public float heatOut = 0f;

        // per-recipe output routing (0-3 only)
        private int[][] itemOutDirs;
        private int[][] liquidOutDirs;

        public MCRecipe currentRecipe()
        {
            int idx = Mathf.clamp(recipeIndex, 0, recipes.size - 1);
            return recipes.get(idx);
        }

        public void setRecipeIndex(int idx)
        {
            int clamped = Mathf.clamp(idx, 0, recipes.size - 1);
            if(recipeIndex != clamped)
            {
                recipeIndex = clamped;
                // Reset progress when switching recipes to avoid mixed IO.
                progress = 0f;
                warmup = 0f;
            }
        }

        private void ensureRouting()
        {
            if(itemOutDirs == null || itemOutDirs.length != recipes.size)
            {
                itemOutDirs = new int[recipes.size][];
            }
            if(liquidOutDirs == null || liquidOutDirs.length != recipes.size)
            {
                liquidOutDirs = new int[recipes.size][];
            }

            for(int i = 0; i < recipes.size; i++)
            {
                MCRecipe r = recipes.get(i);
                int itemLen = r.itemOutputs == null ? 0 : r.itemOutputs.length;
                int liquidLen = r.liquidOutputs == null ? 0 : r.liquidOutputs.length;

                if(itemOutDirs[i] == null || itemOutDirs[i].length != itemLen)
                {
                    itemOutDirs[i] = new int[itemLen];
                    for(int j = 0; j < itemLen; j++)
                    {
                        int dir = itemOutputDirections.length > j ? itemOutputDirections[j] : 0;
                        if(dir < 0) dir = 0;
                        itemOutDirs[i][j] = dir & 0x3;
                    }
                }

                if(liquidOutDirs[i] == null || liquidOutDirs[i].length != liquidLen)
                {
                    liquidOutDirs[i] = new int[liquidLen];
                    for(int j = 0; j < liquidLen; j++)
                    {
                        int dir = liquidOutputDirections.length > j ? liquidOutputDirections[j] : 0;
                        if(dir < 0) dir = 0;
                        liquidOutDirs[i][j] = dir & 0x3;
                    }
                }
            }
        }

        private int getItemOutputDir(int recipeIdx, int outIdx)
        {
            ensureRouting();
            if(recipeIdx < 0 || recipeIdx >= itemOutDirs.length) return -1;
            if(itemOutDirs[recipeIdx].length <= 1) return -1;
            if(outIdx < 0 || outIdx >= itemOutDirs[recipeIdx].length) return -1;
            return itemOutDirs[recipeIdx][outIdx];
        }

        private int getLiquidOutputDir(int recipeIdx, int outIdx)
        {
            ensureRouting();
            if(recipeIdx < 0 || recipeIdx >= liquidOutDirs.length) return -1;
            if(liquidOutDirs[recipeIdx].length <= 1) return -1;
            if(outIdx < 0 || outIdx >= liquidOutDirs[recipeIdx].length) return -1;
            return liquidOutDirs[recipeIdx][outIdx];
        }

        private void setRoute(int recipeIdx, int packed)
        {
            int idx = Mathf.clamp(recipeIdx, 0, recipes.size - 1);
            int type = routeType(packed);
            int outputIndex = routeIndex(packed);
            int dir = routeDir(packed) & 0x3;

            ensureRouting();

            if(type == ROUTE_TYPE_ITEM)
            {
                if(outputIndex >= 0 && outputIndex < itemOutDirs[idx].length)
                {
                    itemOutDirs[idx][outputIndex] = dir;
                }
            }
            else if(type == ROUTE_TYPE_LIQUID)
            {
                if(outputIndex >= 0 && outputIndex < liquidOutDirs[idx].length)
                {
                    liquidOutDirs[idx][outputIndex] = dir;
                }
            }
        }

        @Override
        public void buildConfiguration(Table table)
        {
            ensureRouting();

            table.table(t -> {
                t.defaults().size(52f);

                int cols = 4;
                for(int i = 0; i < recipes.size; i++)
                {
                    final int idx = i;
                    final MCRecipe r = recipes.get(i);

                    TextureRegion icon = pickRecipeIcon(r);

                    t.button(new TextureRegionDrawable(icon), Styles.clearTogglei, () -> configure(idx))
                        .checked(button -> recipeIndex == idx)
                        .tooltip(r.name == null ? "" : localizeRecipeName(r.name));

                    if((i + 1) % cols == 0)
                    {
                        t.row();
                    }
                }
            });

            table.row();
            table.table(Styles.grayPanel, t -> {
                t.left().top().defaults().left();
                t.add("[lightgray]Output directions (F/R/B/L)").row();

                MCRecipe r = currentRecipe();
                int ridx = recipeIndex;

                if(r.itemOutputs != null && r.itemOutputs.length > 1)
                {
                    for(int i = 0; i < r.itemOutputs.length; i++)
                    {
                        final int outIdx = i;
                        ItemStack out = r.itemOutputs[i];
                        t.table(row -> {
                            row.left();
                            row.image(out.item.uiIcon).size(24f).padRight(6f);
                            for(int d = 0; d < 4; d++)
                            {
                                final int dir = d;
                                row.button(dirIcon(dir), Styles.clearTogglei, () ->
                                    configure(new Point2(ridx, packRoute(ROUTE_TYPE_ITEM, outIdx, dir))))
                                    .checked(b -> getItemOutputDir(ridx, outIdx) == dir)
                                    .size(32f, 24f).padRight(2f);
                            }
                        }).row();
                    }
                }

                if(r.liquidOutputs != null && r.liquidOutputs.length > 1)
                {
                    for(int i = 0; i < r.liquidOutputs.length; i++)
                    {
                        final int outIdx = i;
                        LiquidStack out = r.liquidOutputs[i];
                        t.table(row -> {
                            row.left();
                            row.image(out.liquid.uiIcon).size(24f).padRight(6f);
                            for(int d = 0; d < 4; d++)
                            {
                                final int dir = d;
                                row.button(dirIcon(dir), Styles.clearTogglei, () ->
                                    configure(new Point2(ridx, packRoute(ROUTE_TYPE_LIQUID, outIdx, dir))))
                                    .checked(b -> getLiquidOutputDir(ridx, outIdx) == dir)
                                    .size(32f, 24f).padRight(2f);
                            }
                        }).row();
                    }
                }
            }).growX().padTop(6f);
        }

        @Override
        public void updateTile()
        {
            MCRecipe r = currentRecipe();

            // Calculate incoming heat if used.
            if(r.heatRequirement > 0f)
            {
                heatIn = calculateHeat(sideHeat);
            }
            else
            {
                heatIn = 0f;
            }

            if(efficiency > 0f)
            {
                progress += getProgressIncrease(r.craftTime);
                warmup = Mathf.approachDelta(warmup, warmupTarget(), r.warmupSpeed);

                // continuous liquid output
                if(r.liquidOutputs != null && r.liquidOutputs.length > 0)
                {
                    float inc = getProgressIncrease(1f);
                    for(int i = 0; i < r.liquidOutputs.length; i++)
                    {
                        LiquidStack out = r.liquidOutputs[i];
                        float space = liquidCapacity - liquids.get(out.liquid);
                        if(space <= 0.0001f) continue;
                        handleLiquid(this, out.liquid, Math.min(out.amount * inc, space));
                    }
                }

                Effect ue = r.updateEffect == null ? defaultUpdateEffect : r.updateEffect;
                float chance = r.updateEffect == null ? defaultUpdateEffectChance : r.updateEffectChance;
                float spread = r.updateEffect == null ? defaultUpdateEffectSpread : r.updateEffectSpread;

                if(wasVisible && ue != Fx.none && Mathf.chanceDelta(chance))
                {
                    ue.at(x + Mathf.range(size * spread), y + Mathf.range(size * spread));
                }
            }
            else
            {
                warmup = Mathf.approachDelta(warmup, 0f, r.warmupSpeed);
            }

            // heat output behavior
            if(r.heatOutput > 0f)
            {
                heatOut = Mathf.approachDelta(heatOut, r.heatOutput * efficiency, heatOutputWarmupRate * delta());
            }
            else
            {
                heatOut = Mathf.approachDelta(heatOut, 0f, heatOutputWarmupRate * delta());
            }

            totalProgress += warmup * Time.delta;

            if(progress >= 1f)
            {
                craft(r);
            }

            dumpOutputs();
        }

        private void craft(MCRecipe r)
        {
            consume();

            if(r.itemOutputs != null)
            {
                for(int o = 0; o < r.itemOutputs.length; o++)
                {
                    ItemStack out = r.itemOutputs[o];
                    int dir = getItemOutputDir(recipeIndex, o);
                    for(int i = 0; i < out.amount; i++)
                    {
                        offloadDirected(out.item, dir);
                    }
                }
            }

            Effect ce = r.craftEffect == null ? defaultCraftEffect : r.craftEffect;
            if(wasVisible && ce != Fx.none)
            {
                ce.at(x, y);
            }

            progress %= 1f;
        }

        public void dumpOutputs()
        {
            MCRecipe r = currentRecipe();

            // dump union of all possible outputs to avoid stranded items when switching recipes
            if(r.itemOutputs != null && r.itemOutputs.length > 0 && timer(timerDump, dumpTime / timeScale))
            {
                for(int i = 0; i < r.itemOutputs.length; i++)
                {
                    int dir = getItemOutputDir(recipeIndex, i);
                    dumpItemDirected(r.itemOutputs[i].item, dir);
                }
            }
            else if(allOutputItems != null && timer(timerDump, dumpTime / timeScale))
            {
                for(int i = 0; i < allOutputItems.length; i++)
                {
                    dump(allOutputItems[i]);
                }
            }

            // If current recipe has explicit liquid outputs, respect output directions for this recipe.
            if(r.liquidOutputs != null && r.liquidOutputs.length > 0)
            {
                for(int i = 0; i < r.liquidOutputs.length; i++)
                {
                    int dir = getLiquidOutputDir(recipeIndex, i);
                    dumpLiquid(r.liquidOutputs[i].liquid, 2f, dir);
                }
                return;
            }

            if(allOutputLiquids != null)
            {
                for(int i = 0; i < allOutputLiquids.length; i++)
                {
                    dumpLiquid(allOutputLiquids[i], 2f, -1);
                }
            }
        }

        private void offloadDirected(Item item, int outputDir)
        {
            if(outputDir == -1)
            {
                offload(item);
                return;
            }

            produced(item, 1);
            int dump = this.cdump;

            for(int i = 0; i < proximity.size; i++)
            {
                incrementDump(proximity.size);
                Building other = proximity.get((i + dump) % proximity.size);
                if((outputDir + rotation) % 4 != relativeTo(other)) continue;

                if(other.acceptItem(self(), item) && canDump(other, item))
                {
                    other.handleItem(self(), item);
                    return;
                }
            }

            handleItem(self(), item);
        }

        private boolean dumpItemDirected(Item item, int outputDir)
        {
            if(outputDir == -1)
            {
                return dump(item);
            }
            if(!block.hasItems || items.total() == 0 || proximity.size == 0 || !items.has(item)) return false;

            int dump = this.cdump;
            for(int i = 0; i < proximity.size; i++)
            {
                incrementDump(proximity.size);
                Building other = proximity.get((i + dump) % proximity.size);
                if((outputDir + rotation) % 4 != relativeTo(other)) continue;

                if(other.acceptItem(self(), item) && canDump(other, item))
                {
                    other.handleItem(self(), item);
                    items.remove(item, 1);
                    return true;
                }
            }

            return false;
        }

        @Override
        public boolean shouldConsume()
        {
            MCRecipe r = currentRecipe();

            // output item fullness
            if(r.itemOutputs != null)
            {
                for(int i = 0; i < r.itemOutputs.length; i++)
                {
                    ItemStack out = r.itemOutputs[i];
                    if(items.get(out.item) + out.amount > itemCapacity)
                    {
                        return false;
                    }
                }
            }

            // output liquid fullness
            if(r.liquidOutputs != null && r.liquidOutputs.length > 0 && !ignoreLiquidFullness)
            {
                boolean allFull = true;
                for(int i = 0; i < r.liquidOutputs.length; i++)
                {
                    LiquidStack out = r.liquidOutputs[i];
                    if(liquids.get(out.liquid) >= liquidCapacity - 0.001f)
                    {
                        if(!dumpExtraLiquid)
                        {
                            return false;
                        }
                    }
                    else
                    {
                        allFull = false;
                    }
                }

                if(allFull)
                {
                    return false;
                }
            }

            return enabled;
        }

        public float warmupTarget()
        {
            MCRecipe r = currentRecipe();
            if(r.heatRequirement <= 0f)
            {
                return 1f;
            }
            return Mathf.clamp(heatIn / r.heatRequirement);
        }

        @Override
        public float efficiencyScale()
        {
            MCRecipe r = currentRecipe();
            if(r.heatRequirement <= 0f)
            {
                return 1f;
            }

            float req = r.heatRequirement;
            float over = Math.max(heatIn - req, 0f);

            return Math.min(Mathf.clamp(heatIn / req) + over / req * overheatScale, maxHeatEfficiency);
        }

        @Override
        public float warmup()
        {
            return warmup;
        }

        @Override
        public float totalProgress()
        {
            return totalProgress;
        }

        @Override
        public float progress()
        {
            return Mathf.clamp(progress);
        }

        @Override
        public int getMaximumAccepted(Item item)
        {
            return itemCapacity;
        }

        @Override
        public boolean shouldAmbientSound()
        {
            return efficiency > 0f;
        }

        @Override
        public boolean acceptItem(Building source, Item item)
        {
            if(!block.hasItems || items == null) return false;
            MCRecipe r = currentRecipe();
            if(r.itemInputs == null || r.itemInputs.length == 0) return false;

            for(int i = 0; i < r.itemInputs.length; i++)
            {
                if(r.itemInputs[i].item == item)
                {
                    return items.get(item) < getMaximumAccepted(item);
                }
            }

            return false;
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid)
        {
            if(!block.hasLiquids || liquids == null) return false;
            MCRecipe r = currentRecipe();
            if(r.liquidInputs == null || r.liquidInputs.length == 0) return false;

            for(int i = 0; i < r.liquidInputs.length; i++)
            {
                if(r.liquidInputs[i].liquid == liquid)
                {
                    return liquids.get(liquid) < block.liquidCapacity - 0.0001f;
                }
            }

            return false;
        }

        @Override
        public float getPowerProduction()
        {
            MCRecipe r = currentRecipe();
            return r.powerProduce;
        }

        // HeatConsumer
        @Override
        public float[] sideHeat()
        {
            return sideHeat;
        }

        @Override
        public float heatRequirement()
        {
            return currentRecipe().heatRequirement;
        }

        // HeatBlock
        @Override
        public float heat()
        {
            return heatOut;
        }

        @Override
        public float heatFrac()
        {
            float out = currentRecipe().heatOutput;
            if(out <= 0f) return 0f;
            return heatOut / out;
        }

        @Override
        public double sense(LAccess sensor)
        {
            if(sensor == LAccess.progress) return progress();
            return super.sense(sensor);
        }

        @Override
        public void write(Writes write)
        {
            super.write(write);
            write.i(recipeIndex);
            write.f(progress);
            write.f(warmup);
            write.f(totalProgress);
            write.f(heatOut);
            ensureRouting();
            write.s(recipes.size);
            for(int i = 0; i < recipes.size; i++)
            {
                write.s(itemOutDirs[i].length);
                for(int j = 0; j < itemOutDirs[i].length; j++)
                {
                    write.i(itemOutDirs[i][j]);
                }
                write.s(liquidOutDirs[i].length);
                for(int j = 0; j < liquidOutDirs[i].length; j++)
                {
                    write.i(liquidOutDirs[i][j]);
                }
            }
        }

        @Override
        public void read(Reads read, byte revision)
        {
            super.read(read, revision);
            recipeIndex = read.i();
            progress = read.f();
            warmup = read.f();
            totalProgress = read.f();
            heatOut = read.f();
            if(revision >= 1)
            {
                ensureRouting();
                int recipeCount = read.s();
                for(int i = 0; i < recipeCount; i++)
                {
                    int itemLen = read.s();
                    for(int j = 0; j < itemLen; j++)
                    {
                        int dir = read.i() & 0x3;
                        if(i < itemOutDirs.length && j < itemOutDirs[i].length)
                        {
                            itemOutDirs[i][j] = dir;
                        }
                    }

                    int liquidLen = read.s();
                    for(int j = 0; j < liquidLen; j++)
                    {
                        int dir = read.i() & 0x3;
                        if(i < liquidOutDirs.length && j < liquidOutDirs[i].length)
                        {
                            liquidOutDirs[i][j] = dir;
                        }
                    }
                }
            }
        }

        @Override
        public byte version()
        {
            return 1;
        }
    }
}
