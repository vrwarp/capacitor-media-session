package io.github.jofr.capacitor.mediasessionplugin;

import androidx.annotation.Nullable;
import androidx.media3.session.CommandButton;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Single source of truth for the standard Media Session actions and the mapping from the
 * TypeScript {@code MediaSessionActionIcon} string literals to Media3 {@link CommandButton}
 * {@code ICON_*} constants.
 *
 * Any registered action string that is not one of {@link #STANDARD_ACTIONS} is treated as a
 * <em>custom action</em>: it is published as an extra button in the session's custom layout and
 * routed back to JavaScript through {@code MediaSession.Callback.onCustomCommand}, instead of
 * flowing through the {@code supportedActions} -> {@code Player.Commands} switch in
 * {@link WebViewProxyPlayer}.
 */
final class CustomActions {
    private CustomActions() {}

    /** The eight actions defined by the Media Session Web API. Everything else is custom. */
    static final Set<String> STANDARD_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "play",
        "pause",
        "seekto",
        "seekforward",
        "seekbackward",
        "nexttrack",
        "previoustrack",
        "stop"
    )));

    static boolean isStandard(String action) {
        return STANDARD_ACTIONS.contains(action);
    }

    static boolean isCustom(String action) {
        return action != null && !action.isEmpty() && !STANDARD_ACTIONS.contains(action);
    }

    /**
     * Resolves a {@code MediaSessionActionIcon} literal (see {@code definitions.ts}) to a Media3
     * {@link CommandButton} {@code ICON_*} constant. Unknown or {@code null} values fall back to
     * {@link CommandButton#ICON_UNDEFINED}.
     */
    static int iconConstant(String icon) {
        if (icon == null) {
            return CommandButton.ICON_UNDEFINED;
        }
        switch (icon) {
            case "play": return CommandButton.ICON_PLAY;
            case "pause": return CommandButton.ICON_PAUSE;
            case "stop": return CommandButton.ICON_STOP;
            case "next": return CommandButton.ICON_NEXT;
            case "previous": return CommandButton.ICON_PREVIOUS;
            case "fast-forward": return CommandButton.ICON_FAST_FORWARD;
            case "rewind": return CommandButton.ICON_REWIND;
            case "skip-forward": return CommandButton.ICON_SKIP_FORWARD;
            case "skip-back": return CommandButton.ICON_SKIP_BACK;
            case "heart": return CommandButton.ICON_HEART_UNFILLED;
            case "heart-filled": return CommandButton.ICON_HEART_FILLED;
            case "star": return CommandButton.ICON_STAR_UNFILLED;
            case "star-filled": return CommandButton.ICON_STAR_FILLED;
            case "thumb-up": return CommandButton.ICON_THUMB_UP_UNFILLED;
            case "thumb-up-filled": return CommandButton.ICON_THUMB_UP_FILLED;
            case "thumb-down": return CommandButton.ICON_THUMB_DOWN_UNFILLED;
            case "thumb-down-filled": return CommandButton.ICON_THUMB_DOWN_FILLED;
            case "shuffle": return CommandButton.ICON_SHUFFLE_OFF;
            case "shuffle-on": return CommandButton.ICON_SHUFFLE_ON;
            case "repeat": return CommandButton.ICON_REPEAT_OFF;
            case "bookmark": return CommandButton.ICON_BOOKMARK_UNFILLED;
            case "bookmark-filled": return CommandButton.ICON_BOOKMARK_FILLED;
            case "plus": return CommandButton.ICON_PLUS;
            case "minus": return CommandButton.ICON_MINUS;
            default: return CommandButton.ICON_UNDEFINED;
        }
    }

    /**
     * An ordered specification for a single custom-action button: the action id (also the
     * {@code SessionCommand.customAction}), its display label, the resolved Media3 icon constant,
     * an optional raw {@code iconUri} string (a custom drawable URI, parsed into an
     * {@code android.net.Uri} only in the service's {@code buildButton} so this class stays free of
     * Android URI types) which takes precedence over the icon constant, and whether the button is
     * enabled (defaults to {@code true} at the call site).
     */
    static final class CustomActionSpec {
        final String id;
        final String label;
        final int iconConstant;
        @Nullable
        final String iconUri;
        final boolean enabled;

        CustomActionSpec(String id, String label, int iconConstant, @Nullable String iconUri, boolean enabled) {
            this.id = id;
            this.label = label;
            this.iconConstant = iconConstant;
            this.iconUri = iconUri;
            this.enabled = enabled;
        }
    }
}
