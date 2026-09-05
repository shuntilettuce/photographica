package dev.shunti.snapmatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if >=1.21.10 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}
//? if >=1.21.10 {
import net.minecraft.util.Identifier;
//?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class SnapmaticaClient implements ClientModInitializer {


    //? if >=1.21.10 {
    private static final KeyBinding.Category SNAPMATICA_CATEGORY =
            new KeyBinding.Category(Identifier.of("snapmatica", "snapmatica"));
    //?}

    // ── Key Bindings ─────────────────────────────────────────────────────────────
    private static KeyBinding shootKey;
    private static KeyBinding settingsKey;
    private static KeyBinding viewfinderSneakKey;  // toggle sneak-to-viewfinder mode
    private static KeyBinding orientationKey;       // toggle portrait/landscape framing
    private static KeyBinding recordKey;            // start/stop video recording
    private static KeyBinding pinKey;               // drone mode: drop/clear the orbit pin
    private static KeyBinding freecamLockKey;       // freecam: lock camera, hand WASD/mouse back to the player
    private static KeyBinding pathMenuKey;          // camera path: open the add/play/clear/duration menu
    // ── Camera state (client-side only, no server sync needed) ───────────────────
    public static float aperture = 5.6f;

    /**
     * Narrowest and widest f-number the barrel can physically reach, regardless of zoom.
     * The blades cannot open wider than the barrel, nor close past their own limit, so the
     * derived f-number below is clamped to this.
     */
    public static final float APERTURE_WIDEST   = 1.4f;
    public static final float APERTURE_NARROWEST = 32.0f;

    /**
     * Diameter of the entrance pupil in mm — the physical opening the blades form.
     *
     * <p>This, not the f-number, is what the aperture ring actually sets. An f-number is only
     * the ratio N = f / D, so it is not independent of focal length: hold the blades still and
     * zoom in, and N climbs on its own. That is why a kit zoom is "f/3.5-5.6" — same blades,
     * different focal length. Treating N as a free-standing knob (as this did) made the lens
     * behave like nothing that exists.
     *
     * <p>Set whenever the aperture is adjusted, consumed whenever the focal length changes.
     */
    public static float apertureDiameterMm = 50.0f / 5.6f;

    /** Records the blade opening implied by the current f-number and focal length. */
    public static void syncApertureDiameter() {
        if (focalLengthMm > 0 && aperture > 0f) apertureDiameterMm = focalLengthMm / aperture;
    }

    /**
     * Re-derives the f-number after a focal-length change, with the blades left where they are.
     * Clamped to what the barrel can do — at the wide end the blades would have to open past
     * the barrel, so N floors out and the diameter is re-synced to the truth.
     */
    public static void applyFocalLengthToAperture() {
        if (apertureDiameterMm <= 0f || focalLengthMm <= 0) return;
        float n = focalLengthMm / apertureDiameterMm;
        aperture = Math.max(APERTURE_WIDEST, Math.min(APERTURE_NARROWEST, n));
        if (aperture != n) syncApertureDiameter();   // hit a stop; blades really did move
        updateAutoValues();
    }
    public static int shutterSpeedIdx = 10;      // index into SHUTTER_SECONDS[] (1/30)
    public static int iso = 400;
    /** Where the image is ACTUALLY focused. Eased toward {@link #focusTarget}. */
    /**
     * Millimetres of subject distance per block — how big the world is to the lens.
     *
     * <p>The single strongest control over how much anything blurs, and the one thing the
     * optics cannot work out for themselves: a Minecraft block is whatever the person who
     * laid it says it is. Built to the usual "one block is a metre" convention a subject five
     * blocks away is five metres off and a fast prime barely separates it; built to the scale
     * a city or a railway is normally made at, the same five blocks are closer to two metres
     * and the same lens throws the background right out. Neither is more correct, so it is a
     * setting rather than a constant.
     *
     * <p>Kept in millimetres because that is the unit the thin-lens formula wants; the screen
     * shows it as centimetres per block, which is how a builder thinks about it.
     */
    public static float dofScaleMm = EvfBlurRenderer.DOF_SCALE_STILL;

    public static float focusDistance = 5.0f;

    /**
     * Average the live view over successive frames, so the gather can spend more taps on it
     * than one frame can hold.
     *
     * <p>One fullscreen blend, no geometry, and NOTHING MOVES — see {@link LiveAperture} for
     * why the version of this that also walked the pupil was removed rather than made optional.
     */
    public static boolean liveTemporalIntegration = true;

    /**
     * Take the photograph by integrating the aperture rather than by blurring one frame.
     *
     * <p>Off by default because it is not free: it costs a rendered frame per pupil sample, so
     * the shutter takes a second or two of real time instead of an instant, and anything that
     * moves during it smears. On, and the defocus stops being a reconstruction — see
     * {@link ApertureIntegration}. The viewfinder is unaffected either way; the gather owns the
     * live view because a live view cannot spend two hundred frames on one picture.
     */
    public static boolean apertureIntegration = false;


    /**
     * Pupil samples per photograph. The bokeh is the average of this many views of the scene,
     * so too few reads as a ring of ghosts rather than as a disc — the same way a real
     * accumulation would. 64 is clean for ordinary apertures; a very wide one spreads the same
     * samples over a bigger disc and wants more.
     */
    public static int apertureSamples = 64;

    /**
     * Write every pupil sample of a burst to disk, alongside what each one metered at.
     *
     * <p>A diagnostic, not a feature. The sum is the only thing normally kept, so when a
     * photograph comes out wrong there is no way to tell which of the parts was wrong — whether
     * the parallax moved at all, whether a shader pack's adaptation re-metered between samples,
     * whether some viewpoints landed inside geometry. With this on, the sub-frames are there to
     * be looked at one by one, unnormalised, next to a text index of their offsets and gains.
     */
    public static boolean apertureDebugSamples = false;

    /**
     * Where the focus ring is set — the destination, not the current state.
     *
     * <p>Manual focus used to write straight to {@link #focusDistance}, so every scroll click
     * was an instant jump. Small clicks hid it, but the last step to infinity multiplies the
     * distance tenfold in one go and the image snapped. Splitting ring position from lens
     * position lets the same rack that autofocus uses carry manual focus too.
     */
    public static float focusTarget = 5.0f;
    /**
     * Focus-distance sentinel meaning "optical infinity" (no finite subject). Set far
     * above any real Minecraft raycast (≤1000) or Distant Horizons (km-scale) distance so
     * it never collides with a measurement — a genuine 2000 m subject focuses finitely at
     * 2000 m, and only sky / no-hit collapses to infinity.
     */
    public static final float FOCUS_INFINITY = 100000.0f;
    public static int focalLengthMm = 50;
    public static int lensType = 1;               // LensKind.PRIME_50MM
    public static int exposureMode = 0;           // M (manual)
    public static int focusMode = 0;              // MF (manual focus)
    public static boolean motionBlur = false;

    /**
     * Highlights high-contrast edges near the focus distance in the viewfinder, the same aid
     * a real mirrorless body draws for manual focus. Never baked into a photo or a recorded
     * frame — see {@code EvfBlurRenderer.applyBlur}'s {@code showPeaking} computation.
     */
    public static boolean focusPeaking = false;

    /**
     * A narrower-dynamic-range "look" — lifted/crushed shadows and a soft-then-hard highlight
     * rolloff, layered over the final image. Unlike everything else in the lens simulation this
     * is not derived from real optics: Minecraft's own framebuffer is already tonemapped,
     * clamped LDR colour, so there is no true scene radiance left to re-expose against a
     * sensor's actual response curve — this recreates the LOOK of limited headroom, not the
     * physics of it, which is why it defaults off and stays a separate toggle rather than
     * something the lens model derives on its own.
     */
    public static boolean dynamicRangeSim = false;

    /**
     * How many stops of scene brightness the simulated sensor captures before {@link
     * #dynamicRangeSim}'s crush/rolloff sets in — no real camera lets you dial this, but this
     * mod already has world scale and non-existent super-tele lenses, so one more knob that
     * doesn't exist on a real body is nothing new. Narrower reads as a cheaper sensor: earlier,
     * harder shadow crush and highlight clipping. Also caps how many stops of metered darkness
     * {@link #updateMetering} will chase at all — see its doc for why a narrow range should
     * also mean a meter that gives up sooner rather than one that "fixes" a scene no sensor
     * this narrow could actually capture.
     */
    public static float dynamicRangeStops = 8.0f;

    /**
     * Output container for a saved photo: 0 = PNG (default, unchanged from before this
     * setting existed), 1 = JPG, 2 = DNG (Linear DNG — see {@link PhotoCapture} and {@link
     * DngWriter}).
     *
     * <p>The DNG branch skips several of THIS MOD'S OWN destructive post-processing steps
     * (exposure multiply+clamp, tone curve, highlight rolloff, and EvfBlurRenderer's
     * DynamicRangeSim crush) that the PNG/JPG branches still bake in exactly as before — see
     * PhotoCapture.applyPhotoEffects's {@code rawCapture} branches for the full list and the
     * reasoning for each. It does NOT recover any dynamic range Minecraft's own 8-bit RGBA8
     * framebuffer already discarded before this mod ever reads it back; it only avoids adding
     * MORE irreversible loss on top of that, in a container real raw software (Lightroom,
     * darktable, RawTherapee) opens as a genuine raw file.
     */
    public static int photoFormat = 0;
    public static final int PHOTO_FORMAT_PNG = 0;
    public static final int PHOTO_FORMAT_JPG = 1;
    public static final int PHOTO_FORMAT_DNG = 2;

    /**
     * AF area: false is a single ray straight down the centre of the reticle ("spot" — the
     * only behaviour this mod had), true samples a small cluster of rays around it and
     * focuses on whichever is NEAREST across the cluster ("zone" — see
     * {@code PhotoCapture#nearestSubjectDistance}). A real camera's subject-recognition
     * autofocus is well past anything worth building here, but "the reticle has to sit
     * exactly on the subject, pixel for pixel" is a spot-metering-shaped problem this mod
     * already has an answer for elsewhere (see {@link #dynamicRangeSim}'s own evaluative
     * metering) — zone AF is the same idea applied to focus distance instead of exposure.
     */
    public static boolean focusAreaWide = false;

    /**
     * Ambient depth of field: the lens applied to ordinary play, not to a photograph.
     *
     * <p>Every setting it uses is its OWN — see {@link #ambientAperture} and {@link
     * #ambientDofScaleMm}. That is deliberate rather than a shortcut: the aperture you want for
     * a photograph and the aperture you want to walk around behind are different numbers, and
     * sharing them would mean every shot re-tuned the world and every walk re-tuned the camera.
     * The camera's own dials, its focus ring, its exposure, its white balance and its
     * dynamic-range curve are all untouched by this mode and untouched BY it.
     *
     * <p>Depth of field only. Distortion and chromatic aberration are properties of the lens
     * the CAMERA is carrying, and bowing or fringing the view someone is playing through is a
     * different proposition from bowing a photograph they chose to take. Exposure, white
     * balance and the tone curve are likewise the camera's, and are skipped here.
     *
     * <p>Suppressed while the viewfinder is up or a capture is running, where the camera's own
     * optics take over — the two never run at once.
     */
    public static boolean ambientDof = false;

    /**
     * The ambient mode's own f-number. Nothing to do with {@link #aperture}.
     *
     * <p>Two stops down from where this started. f/2.8 is a fine aperture to reach for on a
     * photograph, but this one is running the entire time you play: at the world scale below,
     * looking down at the ground two blocks ahead threw terrain at 20 blocks to about 5.8 px
     * and the player's own feet to 7.6 px, which reads as an effect rather than as depth.
     * f/5.6 lands the same two on 2.9 and 3.8 — noticeable when you look for it, and not
     * otherwise. Measured at 1080p with the default field of view.
     */
    // f/4, not the f/5.6 the camera starts at. The two numbers do different jobs: the camera's
    // is a starting point on a dial the photographer is about to turn, and this one is the whole
    // of the effect for someone who will never open the menu. At the default world scale and the
    // 8-block focus the ambient mode holds, f/5.6 puts 6.4 px of blur on the far field and f/4
    // puts 8.9 — visible without taking the game over. f/2 doubles it again if that is wanted.
    public static float ambientAperture = 4.0f;

    /**
     * The ambient mode's own world scale, in mm per block. Nothing to do with {@link
     * #dofScaleMm} — see {@link #ambientDof}.
     *
     * <p>Same 37.5 cm a block the photo side defaults to, and for the same reason: a Minecraft
     * build reads as a model of something bigger, and the optics should treat it that way
     * whether you are photographing it or standing in it. Raising it to a metre a block was
     * tried as the way to calm the effect down — it is the strongest single control over how
     * heavy this feels, worth about the same as two stops — but softening the LENS instead
     * keeps the aperture number meaning what a photographer expects while leaving the world the
     * size it always was. See {@link #ambientAperture}.
     */
    public static float ambientDofScaleMm = EvfBlurRenderer.DOF_SCALE_STILL;

    /**
     * How much of the gather's sample budget the ambient mode is allowed: 0 performance,
     * 1 balanced, 2 high. A viewfinder is held for seconds and a shutter fires once, so both
     * can spend freely; this one is paid for on every frame forever.
     */
    public static int ambientQuality = 1;

    /**
     * Where the ambient mode is focused, and where it is heading — its own pair, entirely
     * separate from {@link #focusDistance} and {@link #focusTarget}.
     *
     * <p>It has to be separate. {@code AutoFocus.tick} writes the camera's focus ring, and it
     * only runs while the viewfinder is up; letting the ambient mode drive that same ring would
     * mean the camera's focus quietly moved every time you walked anywhere, and you would raise
     * the viewfinder to find the ring somewhere you never put it.
     */
    public static float ambientFocusDistance = 8.0f;
    private static float ambientFocusTarget = 8.0f;
    private static long ambientFocusQueryMs = 0L;

    /**
     * Neutral-density filter strength, in stops of light removed. 0 is no filter.
     *
     * <p>A piece of dark glass in front of the lens, and the only accessory in photography
     * whose entire purpose is to make the picture WORSE-lit on purpose. It buys the two things
     * a bright scene otherwise refuses: a wide aperture in daylight (shallow depth of field at
     * noon, which the exposure triangle alone cannot reach once the shutter has run out of
     * speed), and a long shutter in daylight (moving water and cloud smeared into a trail,
     * which is what this mod's long-exposure accumulation exists to render).
     *
     * <p>Purely multiplicative on the light, so it needs no model of its own: it is exactly
     * {@code ndStops} subtracted from the exposure the settings would otherwise deliver. In
     * Manual that darkens the photograph, which is the honest result of fitting one and not
     * compensating. In Av/Tv/P the camera compensates — see {@link #updateAutoValues}, which
     * deliberately sends every ND stop to the shutter/aperture rather than to ISO.
     *
     * <p>Visible in the viewfinder, which darkens with it the way a real mirrorless finder
     * does — the finder applies the same exposure the capture will (see {@code evf_blur.fsh}
     * Pass 5), so fitting a 10-stop filter in Manual really does leave you composing in the
     * dark, which is the honest reason ND1000 is awkward to use on a real body too.
     */
    public static int ndStops = 0;

    /**
     * Crop factor of the simulated sensor relative to 35 mm full frame — 1.0 is the 36x24 mm
     * frame every distance in this mod was written against, 1.5 an APS-C body, 2.0 Micro Four
     * Thirds, 2.7 a 1-inch compact, 0.79 a 44x33 medium-format back.
     *
     * <p>One number rather than a width and a height, and the 3:2 frame shape is left alone:
     * crop factor is exactly the quantity that carries over between formats (it is the ratio of
     * the frame diagonals), and every place this mod already models — field of view, circle of
     * confusion, the focal length in pixels — depends only on how big the frame is, not on its
     * proportions. Real MFT and medium-format bodies are 4:3 rather than 3:2, but making the
     * frame shape itself a per-sensor property would change what {@code PhotoCapture.frameRect}
     * crops and what the viewfinder draws, for a difference that has nothing to do with the
     * optics this setting exists to move.
     *
     * <p>Because it feeds the field of view AND the depth-of-field maths from the same number,
     * both move together the way they really do: the same 50 mm on APS-C frames like a 75 mm
     * and separates the background like the 50 mm it still is, so matching the framing by
     * stepping back is what actually costs the blur.
     */
    public static float sensorCropFactor = 1.0f;

    /** Height of the simulated frame in mm — 24 mm at full frame, scaled by the crop factor. */
    public static float sensorHeightMm() {
        return 24.0f / Math.max(0.2f, sensorCropFactor);
    }

    /**
     * Where the lens actually forms its image, in mm behind the rear node — which is what the
     * angle of view is really measured against, not the focal length.
     *
     * <p>The two are equal only at infinity. Focusing closer moves the image plane out to
     * {@code v = fS/(S-f)}, straight from the same thin-lens equation the depth-of-field maths
     * already run on, and a longer v over the same frame is a narrower field: the picture gets
     * tighter as the lens focuses down. That is focus breathing — not an effect layered over
     * the optics but a consequence of them, which is why there is no strength to tune here.
     *
     * <p>Returns the focal length unchanged when {@link #focusBreathing} is off (the behaviour
     * before this existed), and clamped hard below: a subject nearer than the focal length
     * cannot be focused at all, and the formula runs away as that distance is approached. 4x is
     * past 1:3 magnification, well beyond any distance the focus ring reaches at a sane world
     * scale, so the clamp only ever catches the degenerate case rather than a real shot.
     */
    public static double imageDistanceMm(double focalMm) {
        if (!focusBreathing) return focalMm;
        return imageDistanceMmPhysical(focalMm);
    }

    /**
     * Where the image really forms, regardless of whether the FIELD is allowed to breathe.
     *
     * <p>Focusing moves the image plane — that is what focusing is — and the field of view
     * following it is the visible consequence, which is why {@link #focusBreathing} can switch
     * that consequence off to imitate a lens corrected for it. The distance itself is not
     * optional, and anything reasoning about the SIZE of the blur circle rather than about the
     * framing needs the real one: the circle scales with the image distance, so at close focus
     * the two answers differ by {@code S/(S-f)} — under 2% at a few metres, a third again at
     * macro distances, and it is exactly the factor that would otherwise put
     * {@link ApertureIntegration} and the gather's thin-lens formula at odds.
     */
    public static double imageDistanceMmPhysical(double focalMm) {
        double subjectMm = (double) focusDistance * dofScaleMm;
        if (subjectMm <= focalMm * 1.25) return focalMm * 4.0;
        return focalMm * subjectMm / (subjectMm - focalMm);
    }

    /**
     * Lateral (transverse) chromatic aberration — the coloured fringing that grows toward the
     * corners because a lens magnifies short wavelengths slightly differently from long ones.
     *
     * <p>Derived from focal length exactly the way {@link EvfBlurRenderer#distortionK} is, and
     * for the same reason: both are properties of how wide the field is, and a wide lens is
     * poor at both. A toggle rather than a permanent fixture only because every modern body and
     * every raw developer corrects lateral CA automatically — switching it off is "the body's
     * lens correction is on", which is a real setting on a real camera, not a cheat.
     */
    public static boolean chromaticAberration = true;

    /**
     * Focus breathing: the field of view narrowing slightly as the lens focuses closer.
     *
     * <p>Not an effect layered on anything — it falls straight out of the thin-lens equation
     * the rest of this mod already runs on. Focusing a lens moves its image plane from f (at
     * infinity) out to v = fS/(S-f), and the field of view is set by the frame's half-height
     * over THAT distance, not over f. A 50 mm focused two metres away really does frame a few
     * percent tighter than the same lens at infinity, and at macro distances it is dramatic.
     *
     * <p>A toggle because it is also the thing cine lenses are sold on NOT doing, and modern
     * bodies ship focus-breathing compensation for video — a shot where the framing must hold
     * while the focus racks is a real reason to want it gone, not a wish for less realism.
     */
    public static boolean focusBreathing = true;

    /**
     * White balance, in kelvin — the colour temperature the camera assumes its illuminant is.
     * {@link #WB_AUTO} (0) means AWB: {@link #updateMetering} estimates it from the scene.
     *
     * <p>The correction is the ratio between daylight and the assumed illuminant, so
     * {@link #WB_DAYLIGHT_K} is exactly the unmodified image: Minecraft's renderer has applied the
     * light's colour to the scene (torchlight really is rendered orange, night really is
     * rendered blue), which is precisely what a sensor would have recorded. White balance does
     * not add a cast — it removes one, and only removes it correctly when the number matches
     * the light that is actually there. Dial 5600 K daylight in a torch-lit cave and it stays
     * orange, the same way it would on a real body.
     */
    public static final int WB_AUTO = 0;
    public static int wbKelvin = WB_AUTO;
    /**
     * The dial reading that applies no correction at all.
     *
     * <p>Daylight, not D65. A camera's Kelvin dial is labelled by the SCENE illuminant, and its
     * daylight setting is by definition the one that renders a daylight scene neutral on a
     * (D65) display — so the identity belongs at daylight's own number. Anchored at 6500 K
     * instead, every setting on the dial sat one notch cool: asking for plain daylight already
     * produced a blue picture before any deliberate cast, and the low end then ran far enough
     * past the blue channel's headroom to clip it outright.
     */
    public static final double WB_DAYLIGHT_K = 5600.0;

    /**
     * What AWB currently believes the scene's illuminant is, in kelvin — eased, and updated by
     * {@link #updateMetering} from the same rays that meter its brightness. Left at the
     * reference (no correction) until metering has actually looked at something.
     */
    private static double meteredKelvin = WB_DAYLIGHT_K;

    /** The temperature the correction should be computed against right now. */
    public static double effectiveWbKelvin() {
        return (wbKelvin == WB_AUTO) ? meteredKelvin : wbKelvin;
    }

    /**
     * Freecam's control feel: off is direct WASD flight (instant response, easy to place
     * exactly); on trades that for inertia, a speed limiter/altitude hold, and orbiting a
     * dropped pin — suited to aerial footage rather than precise still-photo positioning, so
     * it is a separate mode rather than freecam's only behaviour.
     */
    public static boolean droneMode = false;

    /**
     * When true, freecam does not force the player's body into view — see
     * {@link dev.shunti.snapmatica.client.mixin.CameraMixin}, which otherwise sets
     * {@code Camera.thirdPerson = true} specifically so vanilla's "don't render the entity the
     * camera is glued to in first person" rule doesn't also hide it once the camera has flown
     * away. Useful for landscape or wildlife footage where the player's own frozen body has no
     * business being in frame.
     */
    public static boolean freecamHidePlayer = false;

    /** Auto-computed shutter index (used when SS is in AUTO mode). */
    public static int autoShutterIdx = 10;
    /** Auto-computed aperture (used when aperture is in AUTO mode). */
    public static float autoAperture = 5.6f;
    /**
     * The exact, unrounded shutter speed and aperture an auto axis is targeting — what the
     * exposure math should use, as opposed to {@link #autoShutterIdx} / {@link #autoAperture},
     * which are that same target rounded to the nearest marked stop for the READOUT.
     *
     * <p>A real light meter drives a continuously-variable shutter and only shows a rounded
     * number on the dial; the exposure it actually achieves does not care which side of a
     * mark the true value fell on. This mod's meter used to read its OWN rounded display back
     * as the value to expose by, so any axis sitting near a stop boundary — trivially reached
     * by an aperture that moves continuously with zoom — could cross it on an imperceptible
     * change and swing the photo a full stop (2x brightness) between one frame and the next.
     * {@link PhotoProcessor#exposureFactor} and the viewfinder's EV needle read these instead,
     * so a whole stop of rounding error never becomes a whole stop of exposure error.
     */
    public static double autoShutterSecondsIdeal = 1.0 / 60.0;
    public static double autoApertureIdeal = 5.6;
    /**
     * ISO's own version of the two above — equals the manual {@link #iso} whenever nothing is
     * asking it to move, and rises above it only when {@link #updateAutoValues} routes some of
     * a dark scene's metered compensation through here instead of an impractically slow
     * shutter or impractically wide aperture. There is no "ISO priority" exposure mode reading
     * this the way Av/Tv/P read the shutter/aperture ideals — it exists purely as the
     * assist an Av/Tv/P axis reaches for first, same as a real body's Auto-ISO does, before it
     * pushes its OWN axis somewhere impractical.
     */
    public static double autoIsoIdeal = 400;

    /**
     * How many stops darker the metered scene reads than the fixed neutral reference
     * {@link #updateAutoValues} otherwise targets — 0 when {@link #dynamicRangeSim} is off, so
     * auto exposure keeps its existing, scene-blind behaviour there. Positive means darker
     * (needs more exposure): Av/P widens the aperture and/or slows the shutter to compensate,
     * same as a real meter opening up in a cave. See {@link #updateMetering}.
     */
    private static double meteredExtraStops = 0.0;
    public static double getMeteredExtraStops() { return meteredExtraStops; }

    /** When true, sneaking shows the viewfinder overlay (default: enabled). */
    public static boolean viewfinderSneakEnabled = true;

    /** When true, the viewfinder and saved photo use a 2:3 portrait frame instead of 3:2. */
    public static boolean portraitOrientation = false;

    // Shutter speed table (same as Photographica's CameraSettings)
    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
            1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
    };

    @Override
    public void onInitializeClient() {
        // ── Load persisted settings (sneak-viewfinder toggle, etc.) ─────────────
        SnapmaticaConfig.load();

        // ── Register key bindings ───────────────────────────────────────────────
        //? if >=1.21.10 {
        shootKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.shoot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, SNAPMATICA_CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, SNAPMATICA_CATEGORY));
        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.viewfinder_sneak", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, SNAPMATICA_CATEGORY));
        orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.orientation", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, SNAPMATICA_CATEGORY));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, SNAPMATICA_CATEGORY));
        pinKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.pin", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, SNAPMATICA_CATEGORY));
        freecamLockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.freecam_lock", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, SNAPMATICA_CATEGORY));
        pathMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.path_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, SNAPMATICA_CATEGORY));
        //?} else {
        /*shootKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.shoot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, "category.snapmatica"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.snapmatica"));
        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.viewfinder_sneak", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, "category.snapmatica"));
        orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.orientation", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.snapmatica"));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.snapmatica"));
        pinKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.pin", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.snapmatica"));
        freecamLockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.freecam_lock", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "category.snapmatica"));
        pathMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.path_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "category.snapmatica"));
        *///?}

        // ── Tick handler ─────────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Before the early return: the exposure recorder counts client ticks, and it has to
            // keep counting whether or not there is a player to take a photograph with.
            EntityExposure.onClientTick();

            if (client.player == null) return;

            // Toggle the sneak-to-viewfinder mode (persisted across sessions)
            while (viewfinderSneakKey.wasPressed()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
                SnapmaticaConfig.save();
            }

            // Toggle portrait / landscape framing
            while (orientationKey.wasPressed()) {
                portraitOrientation = !portraitOrientation;
                SnapmaticaConfig.save();
            }

            // Recording key: open settings screen when idle, stop directly when recording
            while (recordKey.wasPressed()) {
                if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
                else if (!VideoRecorder.isPostProcessing()) client.setScreen(new VideoRecorderScreen());
            }

            // Shoot key
            if (shootKey.wasPressed()) {
                PhotoCapture.take();
            }

            while (pinKey.wasPressed()) {
                Freecam.togglePin(client);
            }
            while (freecamLockKey.wasPressed()) {
                Freecam.toggleLock(client);
            }
            while (pathMenuKey.wasPressed()) {
                if (client.currentScreen instanceof CameraPathScreen) {
                    client.setScreen(null);
                } else if (Freecam.isActive() && client.currentScreen == null) {
                    client.setScreen(new CameraPathScreen());
                }
            }
            Freecam.tick(client);

            // Settings key
            if (settingsKey.wasPressed()) {
                client.setScreen(new CameraScreen());
            }


            // Auto-focus (AF / MOB) drives focusDistance while the viewfinder is active
            AutoFocus.tick(client);
            // Real scene-brightness metering only matters once there is a dynamic range to
            // actually clip against — see updateMetering.
            updateMetering(client);
            updateAmbientFocus(client);
            // Keep auto exposure values current every tick
            updateAutoValues();
        });

        // ── HUD overlay (viewfinder, blackout, flash, video REC) ────────────────
        HudRenderCallback.EVENT.register(ViewfinderOverlay::render);
        HudRenderCallback.EVENT.register(VideoRecorderHud::render);
        HudRenderCallback.EVENT.register(FreecamHud::render);
        HudRenderCallback.EVENT.register(CameraPathRenderer::render);

        // ── World render end (depth capture, etc.) ──────────────────────────────
        // The depth copy happens BEFORE the translucent pass so glass cannot stamp its own
        // surface distance over the view through it — see PhotoCapture.onBeforeTranslucent().
        // The AF raycast stays at the end of the pass; it is a world query and does not care
        // where in the render it runs. Recording needs no hook of its own — the guard in
        // onBeforeTranslucent() already covers it.
        //? if >=1.21.10 {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ctx -> PhotoCapture.onBeforeTranslucent());
        WorldRenderEvents.END_MAIN.register(ctx -> PhotoCapture.onWorldRenderEnd());
        //?} else {
        /*// The pre-1.21.11 API has no BEFORE_TRANSLUCENT; BEFORE_DEBUG_RENDER sits at the
        // same point — after entities and the opaque terrain, before anything translucent.
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> PhotoCapture.onBeforeTranslucent());
        WorldRenderEvents.LAST.register(ctx -> PhotoCapture.onWorldRenderEnd());
        *///?}

        System.out.println("[Snapmatica] Initialized.");
    }

    /**
     * Reads how dark the scene actually is and updates {@link #meteredExtraStops} — real
     * TTL-style metering, rather than {@link #updateAutoValues}'s fixed reference, which by
     * itself has no idea whether the lens is pointed into a sunlit field or a cave.
     *
     * <p>Gated on {@link #dynamicRangeSim}: without a narrower dynamic range to actually clip
     * highlights or crush shadows against, a scene-aware meter would just relight every photo
     * to the same flat middle grey regardless of the room — which is what a real camera's
     * auto exposure does too, but this mod's existing fixed-reference behaviour was the
     * intentional, predictable default before dynamic range simulation existed, and nothing
     * about turning that simulation off should also turn off a feature it never advertised.
     *
     * <p>Center-weighted over five points (the reticle plus a modest ring around it), not a
     * pure spot meter off the reticle alone — a cheap stand-in for a real body's many-zone
     * evaluative metering. A pure spot reading chased exactly the darkest thing under the
     * reticle, so a wall filling most of the frame with a bright doorway at the EDGE still
     * read as "fully dark" and got compensated as if the doorway weren't there at all — which
     * is what then blew it out to a featureless white once the compensation caught up. Letting
     * the surrounding points pull the average toward whatever bright content is also in frame
     * is what keeps that compensation from chasing one dark point at the expense of everything
     * else in the shot, the same reason a real camera doesn't spot-meter by default either.
     */
    private static void updateMetering(MinecraftClient mc) {
        if (mc.world == null) { meteredExtraStops = 0.0; return; }
        // Nothing downstream reads either result: exposure metering is gated on dynamic range
        // simulation, and the illuminant estimate only feeds AWB. With both off the rays below
        // would be cast and thrown away every tick, so this keeps the cost exactly where it was
        // before white balance existed for anyone using neither.
        if (!dynamicRangeSim && wbKelvin != WB_AUTO) { meteredExtraStops = 0.0; return; }
        net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
        net.minecraft.util.math.Vec3d eye = cameraPos(mc);
        float yaw = camera.getYaw(), pitch = camera.getPitch();
        final double maxDist = 64.0;

        // {yaw offset, pitch offset}, degrees. Centre weighted double a ring of four — enough
        // to notice a bright area at the frame's edge without diluting the reticle's own
        // subject down to an unrecognisable frame-wide average.
        float[][] offsets = {{0, 0}, {18, 0}, {-18, 0}, {0, 18}, {0, -18}};
        double[] weights  = {2, 1, 1, 1, 1};
        double litSum = 0.0, weightSum = 0.0;
        // The same rays also carry the illuminant: Minecraft tracks block light and sky light
        // as separate channels at every position, which is exactly the split a colour
        // temperature needs — one is fire, the other is the sun. Accumulated per channel
        // rather than as the combined level above, since the combined level has already
        // thrown away which of the two was responsible.
        double blockSum = 0.0, skySum = 0.0;
        for (int i = 0; i < offsets.length; i++) {
            net.minecraft.util.math.Vec3d look = net.minecraft.util.math.Vec3d.fromPolar(
                    pitch + offsets[i][1], yaw + offsets[i][0]);
            net.minecraft.util.hit.BlockHitResult hit =
                    AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
            int light, blockLight, skyLight;
            if (hit != null && hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                net.minecraft.util.math.BlockPos lit = hit.getBlockPos().offset(hit.getSide());
                light      = mc.world.getLightLevel(lit);
                blockLight = mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, lit);
                skyLight   = mc.world.getLightLevel(net.minecraft.world.LightType.SKY, lit);
            } else {
                // Nothing hit: the ray left through the sky, which is fully lit by definition
                // and lit by nothing but the sky.
                light = 15; blockLight = 0; skyLight = 15;
            }
            litSum   += light * weights[i];
            blockSum += blockLight * weights[i];
            skySum   += skyLight * weights[i];
            weightSum += weights[i];
        }
        double meteredLight = litSum / weightSum;
        updateWhiteBalance(mc, blockSum / weightSum, skySum / weightSum);

        // Everything below is exposure metering, which only matters once there is a narrower
        // dynamic range to actually clip against — see this method's doc. The illuminant
        // estimate above is not gated on it: a camera's white balance has nothing to do with
        // how much latitude its sensor has.
        if (!dynamicRangeSim) { meteredExtraStops = 0.0; return; }
        // A dead zone at the bright end, not a straight (15 - level) reading from full
        // daylight — ordinary outdoor light swings a few levels between open sun and a
        // tree's shade without the scene actually needing different exposure, and reacting
        // to that read as the whole frame's brightness visibly shifting every time a cloud
        // or a leaf crossed the reticle. Anything at or above NEUTRAL_FLOOR is "outdoors,
        // already fine" and asks for nothing; only once the reading actually drops into
        // shadow/interior territory does compensation start climbing, roughly a stop for
        // every level from there down to true darkness — a look rather than a photometric
        // instrument, and simple enough to tune by eye.
        final double NEUTRAL_FLOOR = 11.0;
        double stopsDark = Math.max(0.0, NEUTRAL_FLOOR - meteredLight);
        // Capped well short of what the raw stops-dark reading alone would ask for, on top of
        // the evaluative averaging above — a meter that fully "fixes" a scene this much darker
        // than neutral is exactly what read as every cave relit to daylight regardless of how
        // dark, the smartphone-HDR look this was meant to avoid, not recreate. A narrower
        // simulated sensor gives up sooner still, same as a real one would.
        double target = Math.max(0.0, Math.min(dynamicRangeStops * 0.4, stopsDark));
        // Eased toward the freshly-read target rather than snapped to it — Minecraft's light
        // scale is coarse (16 discrete steps), so swinging the reticle across a block edge
        // would otherwise jump the metered exposure by a whole stop or more in a single tick,
        // the same reasoning behind every other rack/ease in this mod's autofocus.
        // Slower than the 0.15/tick this started at — a meter that keeps up with every
        // reticle swing instantly read as jumpy/artificial rather than as a camera settling
        // on a new reading. ~0.85s to reach the new target's midpoint, ~2s to mostly settle —
        // noticeable lag, same as a real body's metering has, but still short enough not to
        // feel like it's ignoring you.
        meteredExtraStops += (target - meteredExtraStops) * 0.06;
    }

    // ── White balance ────────────────────────────────────────────────────────────
    /**
     * How completely AWB is allowed to neutralise the cast it finds. Deliberately short of 1:
     * every real camera's auto white balance under-corrects warm light on purpose, because a
     * candlelit room photographed to a perfectly neutral grey no longer reads as candlelit.
     */
    private static final double AWB_STRENGTH = 0.7;

    /**
     * Estimates the scene's illuminant from the light the metering rays landed in, and eases
     * {@link #meteredKelvin} toward it.
     *
     * <p>The illuminant is not guessed from a table of real-world colour temperatures — it is
     * computed from what Minecraft's own renderer actually multiplied the scene by, because
     * THAT is the light the sensor received. Vanilla's {@code lightmap.fsh} builds its block
     * light as
     * <pre>(p, p*((p*0.6+0.4)*0.6+0.4), p*(p*p*0.6+0.4))</pre>
     * for brightness p, which normalised on red is {@code (1, 0.36p+0.64, 0.6p²+0.4)} — warm
     * and low when a torch is far off, converging on white right at the source. Its sky light
     * is {@code mix(vec3(f,f,1), vec3(1,1,1), 0.35)}, white overhead in daylight and strongly
     * blue once the sun has set, which is where Minecraft's cold night comes from.
     *
     * <p>Those two are mixed by how much each is contributing, and the result is converted back
     * to a temperature ({@link #correlatedKelvin}) so both AWB and a manually dialled Kelvin
     * reach the correction through exactly the same path.
     */
    private static void updateWhiteBalance(MinecraftClient mc, double blockLight, double skyLight) {
        // How much light the sky is actually delivering, as opposed to how much of it can see
        // the sky: getLightLevel(SKY, ...) is 15 outdoors at midnight just as it is at noon —
        // it reports exposure to the sky, not the sky's brightness — so it has to be scaled by
        // where the sun is before it can be weighed against a torch.
        long t = Math.floorMod(mc.world.getTimeOfDay(), 24000L);
        // Deliberately not a plain sine of the hour: Minecraft holds full daylight for most of
        // the day and then falls off over about one in-game hour around dusk, so a sine would
        // have the light already half gone by mid-afternoon. Scaling the cosine steeply and
        // clamping reproduces that flat top and fast shoulder — full brightness from roughly
        // t=1000 to t=11500, dark by t=13500, which is where the game actually puts them.
        double sunFactor = Math.max(0.0, Math.min(1.0,
                Math.cos(2.0 * Math.PI * (t - 6000) / 24000.0) * 2.0 + 0.55));
        // Vanilla's own sky-colour parameter, approximated from that rather than read from the
        // lightmap uniform (which is not exposed, and whose accessor has moved between the
        // versions this mod supports). Floored well above 0 at night — a moonlit scene is still
        // lit, and its blue is exactly the cast worth correcting.
        double f = 0.2 + 0.8 * sunFactor;
        double skyR = f + (1.0 - f) * 0.35;   // mix(vec3(f,f,1), vec3(1), 0.35)
        double skyG = skyR;
        double skyB = 1.0;
        // Never fully zero, for the same reason.
        double skyBrightness = Math.max(0.10, sunFactor);

        // Block light's own colour, straight from vanilla's cubic, at the brightness the metered
        // level implies. get_brightness(level) = level / (4 - 3*level), also vanilla's.
        double lvl = Math.max(0.0, Math.min(1.0, blockLight / 15.0));
        double p = lvl / (4.0 - 3.0 * lvl);
        double blockR = 1.0;
        double blockG = 0.36 * p + 0.64;
        double blockB = 0.6 * p * p + 0.4;

        double blockWeight = lvl;
        double skyWeight   = (skyLight / 15.0) * skyBrightness;
        double total = blockWeight + skyWeight;

        double sceneK;
        if (total < 1e-4) {
            // Pitch dark — nothing to balance against, so ask for no correction rather than
            // invent an illuminant for light that isn't there.
            sceneK = WB_DAYLIGHT_K;
        } else {
            sceneK = correlatedKelvin(
                    (blockWeight * blockR + skyWeight * skyR) / total,
                    (blockWeight * blockG + skyWeight * skyG) / total,
                    (blockWeight * blockB + skyWeight * skyB) / total);
            // Held to the range the manual dial itself covers. Minecraft's night sky is bluer
            // than any blackbody, so an unclamped correlated temperature pegs at the top of the
            // locus and asks for a correction no real body's AWB would offer — every one of
            // them stops somewhere around here, and for the same reason.
            sceneK = Math.max(2500.0, Math.min(12000.0, sceneK));
        }

        // Blended in MIREDS (a million over the temperature) rather than in kelvin, which is the
        // only way to mix or interpolate colour temperatures that behaves: the visible
        // difference between 2000 K and 3000 K is enormous and between 8000 K and 9000 K almost
        // nothing, and mireds are defined so that equal steps are equal shifts.
        double refMired = 1e6 / WB_DAYLIGHT_K;
        double targetMired = refMired + (1e6 / sceneK - refMired) * AWB_STRENGTH;

        // Eased, same reasoning (and roughly the same rate) as the exposure meter above: a real
        // body's AWB settles onto a new reading rather than snapping, and walking from sunlight
        // into a torch-lit doorway should not recolour the frame in a single tick.
        double currentMired = 1e6 / Math.max(1000.0, meteredKelvin);
        currentMired += (targetMired - currentMired) * 0.05;
        meteredKelvin = 1e6 / Math.max(1.0, currentMired);
    }

    /**
     * The dial reading whose correction best matches an observed illuminant colour.
     *
     * <p>A scan along the locus rather than a formula, because Minecraft's illuminants are not
     * blackbodies and need not lie on it at all: its night sky is bluer than any temperature
     * can be. "Nearest point on the locus" is exactly what a correlated colour temperature
     * means for a light like that.
     *
     * <p>Matched against each candidate's appearance RELATIVE TO DAYLIGHT, not against its
     * absolute colour, because the observed illuminant is itself relative — Minecraft renders
     * daylight as white, so "white" has to come back as {@link #WB_DAYLIGHT_K} and not as
     * whatever absolute temperature happens to be neutral on a display.
     */
    private static double correlatedKelvin(double r, double g, double b) {
        double tr = Math.log(Math.max(r, 1e-4) / Math.max(g, 1e-4));
        double tb = Math.log(Math.max(b, 1e-4) / Math.max(g, 1e-4));
        double[] day = planckianLinearRgb(WB_DAYLIGHT_K);
        double best = WB_DAYLIGHT_K, bestErr = Double.MAX_VALUE;
        final int STEPS = 96;
        for (int i = 0; i <= STEPS; i++) {
            // 40 to 400 mireds — 25000 K down to 2500 K, the range the manual dial covers.
            double k = 1e6 / (40.0 + (400.0 - 40.0) * i / STEPS);
            double[] c = planckianLinearRgb(k);
            double cr = Math.log((c[0] / day[0]) / (c[1] / day[1]));
            double cb = Math.log((c[2] / day[2]) / (c[1] / day[1]));
            double err = (cr - tr) * (cr - tr) + (cb - tb) * (cb - tb);
            if (err < bestErr) { bestErr = err; best = k; }
        }
        return best;
    }

    /**
     * Per-channel gain that white balance applies, normalised to leave luminance alone.
     *
     * <p>The ratio between daylight and the assumed illuminant, both as LINEAR light — a
     * sensor's channel gain multiplies photons, not the gamma-encoded numbers a framebuffer
     * happens to store, so the shader linearises before applying this and re-encodes after.
     *
     * <p>Normalising by luminance rather than by the green channel keeps a strong correction
     * from also changing the exposure: white balance decides colour, and the exposure triangle
     * is what decides brightness.
     */
    public static float[] whiteBalanceGain() {
        double k = effectiveWbKelvin();
        if (Math.abs(k - WB_DAYLIGHT_K) < 1.0) return new float[]{1f, 1f, 1f};
        double[] ref = planckianLinearRgb(WB_DAYLIGHT_K);
        double[] cur = planckianLinearRgb(k);
        double r = ref[0] / Math.max(cur[0], 1e-4);
        double g = ref[1] / Math.max(cur[1], 1e-4);
        double b = ref[2] / Math.max(cur[2], 1e-4);
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (lum < 1e-4) return new float[]{1f, 1f, 1f};
        r /= lum; g /= lum; b /= lum;
        // Capped by easing the WHOLE vector back toward neutral rather than clipping whichever
        // channel is tallest. Below about 3000 K a blackbody has so little blue left that fully
        // correcting for it needs a gain no diagonal multiply can deliver without pinning that
        // channel at full scale — which is not a colour cast any more but destroyed highlights
        // and a twisted hue. Easing keeps the direction of the correction and only gives up its
        // last part, which is also roughly where a real body's Kelvin range stops caring.
        double max = Math.max(r, Math.max(g, b));
        if (max > WB_MAX_GAIN) {
            double t = (WB_MAX_GAIN - 1.0) / (max - 1.0);
            r = 1 + (r - 1) * t; g = 1 + (g - 1) * t; b = 1 + (b - 1) * t;
        }
        return new float[]{(float) r, (float) g, (float) b};
    }

    private static final double WB_MAX_GAIN = 3.0;

    /**
     * Linear sRGB of a blackbody at {@code kelvin}, at unit luminance.
     *
     * <p>The real chain — Planckian locus in CIE xy, to XYZ, to linear sRGB — rather than the
     * piecewise fit to a blackbody's ON-SCREEN APPEARANCE this used to run on. That fit is
     * built to look right when written straight into a gamma-encoded pixel, which makes it the
     * wrong thing to take a ratio of: a channel gain is a linear quantity, and the two disagree
     * by exactly the transfer function.
     *
     * <p>xy from Kim et al.'s cubic approximation to the locus, the standard one, accurate over
     * 1667-25000 K to far finer than anything here needs.
     */
    private static double[] planckianLinearRgb(double kelvin) {
        double t = Math.max(1667.0, Math.min(25000.0, kelvin));
        double t2 = t * t, t3 = t2 * t;
        double x = (t <= 4000.0)
                ? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
                : -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
        double x2 = x * x, x3 = x2 * x;
        double y;
        if (t <= 2222.0)      y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
        else if (t <= 4000.0) y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
        else                  y =  3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;

        double yy = Math.max(y, 1e-4);
        double bigX = x / yy, bigY = 1.0, bigZ = (1.0 - x - y) / yy;
        return new double[]{
                 3.2404542 * bigX - 1.5371385 * bigY - 0.4985314 * bigZ,
                -0.9692660 * bigX + 1.8760108 * bigY + 0.0415560 * bigZ,
                 0.0556434 * bigX - 0.2040259 * bigY + 1.0572252 * bigZ};
    }

    /**
     * Keeps the ambient mode focused on whatever is under the middle of the screen.
     *
     * <p>Deliberately simple next to {@link AutoFocus}: no focus modes, no zone, no subject
     * tracking, no infinity sentinel to rack to. Ordinary play does not want a focus ring, it
     * wants whatever is being looked at to be sharp — so this is a plain centre ray, throttled
     * and eased, and nothing else.
     *
     * <p>Eased in DIOPTRES (reciprocal distance) rather than in blocks, which is what makes a
     * rack look right: the same visible change takes the same time whether the lens is moving
     * from 2 blocks to 3 or from 50 to infinity, because that is how a focus helicoid actually
     * moves. Easing linearly in blocks would crawl up close and snap in the distance.
     */
    private static void updateAmbientFocus(MinecraftClient mc) {
        if (!ambientDof || mc.world == null) return;
        // The camera owns the optics whenever it is up; nothing to track meanwhile.
        if (viewfinderActive(mc) || VideoRecorder.isRecording()) return;

        long now = System.currentTimeMillis();
        if (now - ambientFocusQueryMs >= 100L) {
            ambientFocusQueryMs = now;
            final double maxDist = 256.0;
            net.minecraft.util.math.Vec3d eye = cameraPos(mc);
            net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
            // The camera's own subject search, not a bare block raycast. A block raycast stops
            // at the first solid face and never sees a mob standing in front of one, so the
            // focus went to the wall behind whatever you were actually looking at. Always the
            // single centre ray: the AF-area setting is the camera's, and this mode is not it.
            double d = PhotoCapture.nearestSubjectDistance(mc, eye,
                    camera.getYaw(), camera.getPitch(), maxDist, new float[][]{{0, 0}});
            // At maxDist the search found nothing — sky, or beyond its reach. Park it there
            // rather than at this mod's infinity sentinel: the ambient mode has no infinity
            // mark to rack to, and a finite far distance keeps the thin-lens maths ordinary.
            ambientFocusTarget = (float) Math.max(0.3, d);
        }

        double from = 1.0 / Math.max(0.3f, ambientFocusDistance);
        double to   = 1.0 / Math.max(0.3f, ambientFocusTarget);
        double eased = from + (to - from) * 0.12;
        ambientFocusDistance = (float) (1.0 / Math.max(1e-4, eased));
    }

    /**
     * Recomputes autoShutterIdx / autoAperture so that the exposure meter stays
     * centred (EV deviation = 0) regardless of ISO or the manually-set value.
     *
     * Reference point: f/5.6, 1/60 s, ISO 400 → EV deviation = 0.
     *   Center condition: ss * 60.0 * (5.6/ap)² * (iso/400) = 1
     *
     * When {@link #dynamicRangeSim} is on, that reference point itself shifts by
     * {@link #meteredExtraStops} — real metering off the scene rather than a fixed target —
     * so Av/P widen the aperture and/or slow the shutter for a dark scene instead of landing
     * on the same settings a bright one would.
     *
     * Call this synchronously whenever aperture, ISO, or exposureMode changes.
     */
    public static void updateAutoValues() {
        // EXP_AV=1 (aperture priority) → SS is auto
        // EXP_TV=2 (shutter priority)  → aperture is auto
        // EXP_P=3  (program)           → both auto (fix ap=5.6)
        boolean ssAuto = (exposureMode == 1 || exposureMode == 3);
        boolean apAuto = (exposureMode == 2 || exposureMode == 3);

        // Split between the shutter/aperture axis and ISO, in that order — the shutter/
        // aperture axis takes the first AXIS_STOPS_BUDGET stops itself (an 8-second exposure
        // was never the goal, but neither is leaning on ISO before the barrel has moved at
        // all: a real body with Auto-ISO still opens up and slows down some first, since
        // ISO is the noisiest way to gain light and the last resort, not the first one).
        // ISO then covers what's left, up to its own ceiling; and if a scene is dark enough
        // to exceed BOTH budgets, the axis takes the overflow too — real information about a
        // genuinely extreme scene, same as it already did before this split existed. Only
        // engages with at least one axis actually auto (an Av/Tv/P axis reaching for help);
        // pure Manual leaves ISO exactly where the dial left it, same as every other axis
        // in that mode.
        double axisStopsUsed = 0.0, isoStopsUsed = 0.0;
        if (ssAuto || apAuto) {
            // An ND filter's stops go ENTIRELY to the shutter/aperture, never to ISO. The
            // filter is fitted precisely to force a slower shutter or a wider aperture in light
            // that would not otherwise allow one; answering it by raising ISO would cancel the
            // only reason to fit it, and hand back a noisier frame for the privilege.
            axisStopsUsed = ndStops;
            if (meteredExtraStops > 0.0) {
                final double AXIS_STOPS_BUDGET = 3.0;
                final double isoCeiling = 25600.0;
                double fromAxis = Math.min(meteredExtraStops, AXIS_STOPS_BUDGET);
                double remainingAfterAxis = meteredExtraStops - fromAxis;
                double isoHeadroomStops = Math.max(0.0, Math.log(isoCeiling / iso) / Math.log(2.0));
                isoStopsUsed = Math.min(remainingAfterAxis, isoHeadroomStops);
                // Plus whatever overflows past ISO's own ceiling.
                axisStopsUsed += fromAxis + (remainingAfterAxis - isoStopsUsed);
            }
        }
        autoIsoIdeal = iso * Math.pow(2.0, isoStopsUsed);
        // What's left for the shutter/aperture targets below to make up. Exactly meterGain
        // with metering off (axisStopsUsed and isoStopsUsed both stay 0 above).
        double axisGain = Math.pow(2.0, axisStopsUsed);

        if (ssAuto) {
            float ap = apAuto ? 5.6f : aperture;
            double targetSS = ap * ap * 400.0 / (60.0 * 31.36 * iso) * axisGain;
            autoShutterIdx = nearestShutterIdx(targetSS);
            // The exact target, not the marked stop it rounds to for the readout — see the
            // field doc. Not clamped to the marked range: over/under by more than the barrel
            // can correct is real information, and exposureFactor()'s own output clamp is
            // what keeps a genuinely extreme scene from blowing out the compensation itself.
            autoShutterSecondsIdeal = targetSS;
        } else {
            autoShutterIdx = shutterSpeedIdx;
            autoShutterSecondsIdeal = SHUTTER_SECONDS[
                    Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
        }

        if (apAuto) {
            double ss = SHUTTER_SECONDS[Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
            double targetAp = 5.6 * Math.sqrt(ss * 60.0 * iso / 400.0 / axisGain);
            // Clamped here, unlike the shutter target above: this one stands in for a lens
            // ring, and a ring cannot go past what the barrel can physically open or close to.
            autoApertureIdeal = Math.max(1.4, Math.min(22.0, targetAp));
            autoAperture = nearestAperture((float) autoApertureIdeal);
        } else {
            autoAperture = aperture;
            autoApertureIdeal = aperture;
        }
    }

    /**
     * Whether the viewfinder (frame, EVF preview, focal-length zoom, autofocus, held-item
     * hiding) should be showing right now — sneaking with the sneak-viewfinder toggle on, OR
     * freecam, which is a photography tool in its own right and should look like one rather
     * than a bare fly-camera. Sneaking itself means nothing while freecam is active, since
     * the player is frozen and sneak state is whatever it happened to be when it toggled on.
     */
    public static boolean viewfinderActive(MinecraftClient mc) {
        if (Freecam.isActive()) return true;
        return viewfinderSneakEnabled && mc.player != null && mc.player.isSneaking();
    }

    /**
     * Where the photograph is actually taken from — the render camera, not {@code mc.player}'s
     * eye. They coincide unless something is driving the camera independently of the player:
     * today that's only {@link Freecam}, but reading it from here rather than from the player
     * directly is what would let an external camera-driving mod (Replay Mod, Flashback, a
     * freecam-type mod) carry autofocus, depth of field and the capture origin along with it
     * automatically, the same way freecam does, since all of those also just move this object.
     */
    public static net.minecraft.util.math.Vec3d cameraPos(MinecraftClient mc) {
        net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
        //? if >=1.21.10 {
        return camera.getCameraPos();
        //?} else {
        /*return camera.getPos();
        *///?}
    }

    /** The render camera's look direction — see {@link #cameraPos}. */
    public static net.minecraft.util.math.Vec3d cameraLook(MinecraftClient mc) {
        net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
        return net.minecraft.util.math.Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
    }

    private static int nearestShutterIdx(double ss) {
        ss = Math.max(1e-6, ss);
        int best = 0; double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < SHUTTER_SECONDS.length; i++) {
            double d = Math.abs(Math.log(SHUTTER_SECONDS[i]) - Math.log(ss));
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static final float[] APERTURE_STOPS = {1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f};

    private static float nearestAperture(float ap) {
        float best = APERTURE_STOPS[0]; float bestDiff = Float.MAX_VALUE;
        for (float a : APERTURE_STOPS) {
            float d = Math.abs(a - ap);
            if (d < bestDiff) { bestDiff = d; best = a; }
        }
        return best;
    }
}

