package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * One user widget's animations: a player per animation however often it plays, as UMG's {@code GetOrAddPlayer}
 * reuses one, and who hears each finish. Every role an animation names is bound to the owner.
 */
final class CgWidgetAnimations {

    private final CgUserWidget owner;
    /** One entry each however often played. */
    private final List<Animation> animations = new ArrayList<>(2);
    @Nullable
    private CgWorld world;

    CgWidgetAnimations(CgUserWidget owner) {
        this.owner = owner;
    }

    /** The world its animations play on: its layer's; the client's while null. */
    void world(@Nullable CgWorld world) {
        this.world = world;
    }

    CgSequencePlayer play(CgSequenceDefinition animation, double start, double end, float speed) {
        Animation a = animation(animation);
        if (a.player != null && !a.player.stopped()) return a.player.jumpTo(start).endAt(end).rate(speed);
        Map<String, CgSequenceTarget> roles = new HashMap<>();
        for (CgSequenceDefinition.Role role : animation.roles()) roles.put(role.name(), owner);
        a.player = CgSequencer.widgets(world != null ? world : CgWorld.client()).play(animation, roles);
        a.player.onFinished(a::finished);
        if (start != 0) a.player.jumpTo(start);
        return a.player.endAt(end).rate(speed);
    }

    double currentTime(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation) return a.player != null && !a.player.stopped() ? a.player.seconds() : 0;
        }
        return 0;
    }

    void stop(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation && a.player != null) {
                a.player.stop();
                a.player = null;
            }
        }
    }

    void stopAll() {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.player != null) {
                a.player.stop();
                a.player = null;
            }
        }
    }

    boolean isPlaying(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation) return a.player != null && !a.player.stopped() && !a.player.finished();
        }
        return false;
    }

    void onFinished(CgSequenceDefinition animation, Runnable listener) {
        animation(animation).listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private Animation animation(CgSequenceDefinition definition) {
        for (int i = 0; i < animations.size(); i++) if (animations.get(i).definition == definition) return animations.get(i);
        Animation a = new Animation(Objects.requireNonNull(definition, "animation"));
        animations.add(a);
        return a;
    }

    /** One animation of the widget: its player while it plays, and who hears it finish. */
    private static final class Animation {
        final CgSequenceDefinition definition;
        final List<Runnable> listeners = new ArrayList<>(1);
        @Nullable
        CgSequencePlayer player;

        Animation(CgSequenceDefinition definition) {
            this.definition = definition;
        }

        void finished() {
            for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
        }
    }
}
