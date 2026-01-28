package mc620.content;

/** Unit helpers to keep recipe definitions readable.
 *  Mindustry rates are generally per-tick; 60 ticks = 1 second.
 */
public final class MCUnits
{
    public static final float TICKS_PER_SECOND = 60f;

    private MCUnits()
    {
    }

    /** Convert per-second rate to per-tick. */
    public static float perSecond(float amountPerSecond)
    {
        return amountPerSecond / TICKS_PER_SECOND;
    }

    /** Convert seconds to ticks. */
    public static float seconds(float seconds)
    {
        return seconds * TICKS_PER_SECOND;
    }
}
