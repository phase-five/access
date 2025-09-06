// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.background;

import io.pfive.access.authentication.UserIdentity;

import static io.pfive.access.util.RandomId.createRandomStringId;

public class BackgroundItem {

    // Identifier of the background item itself, not that of the item being processed.
    public final String id;

    public final String title;
    public final UserIdentity user;
    public final ProgressSink progressSink;

    public BackgroundItem (String title, UserIdentity user) {
        this.id = createRandomStringId();
        this.title = title;
        this.user = user;
        this.progressSink = new ProgressSink(this.id, user);
    }

    public enum State {
        WAITING,
        IN_PROGRESS,
        COMPLETED,
        CANCELED,
        ERRORED,
        ARCHIVED
    }

}
