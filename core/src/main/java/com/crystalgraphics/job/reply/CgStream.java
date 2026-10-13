package com.crystalgraphics.job.reply;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link CgReply} that arrives in pieces: each piece to {@link #onPartial} in order, the whole list to
 * {@link #then} once.
 *
 * <pre>{@code
 * CgStream<Entry> listing = files.list(dir);
 * listing.onPartial(tree::append)          // each page as it lands
 *        .then(all -> status.show(all.size() + " files"));
 * }</pre>
 *
 * A caller that ignores {@code onPartial} reads it like any reply.
 */
public interface CgStream<T> extends CgReply<List<T>> {

    /** Each piece, in order. Registered late, it first replays the pieces already delivered. */
    CgStream<T> onPartial(Consumer<? super T> each);
}
